package com.voiceflow.local.engine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class DictationState {
    IDLE,
    LISTENING,
    PROCESSING,
    SUCCESS
}

class SpeechEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpeechEngine"
        private const val WATCHDOG_TIMEOUT_MS = 60000L // 60s timeout during continuous dictation
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var isSessionActive = false
    @Volatile private var isRecognizerListening = false
    @Volatile private var consecutiveErrors = 0
    @Volatile private var isFinalizing = false
    private var pendingPartial: String? = null

    var onStateChanged: ((DictationState) -> Unit)? = null
    var onAmplitudeUpdated: ((Float) -> Unit)? = null
    var onTranscriptionComplete: ((String) -> Unit)? = null
    var onSessionPolished: ((rawDraft: String, polished: String) -> Unit)? = null

    private val sessionTextBuffer = StringBuilder()

    private val watchdogRunnable = Runnable {
        Log.w(TAG, "Watchdog triggered in recognition loop")
        if (isSessionActive) {
            restartRecognitionLoop()
        } else {
            finalizeSession()
        }
    }

    init {
        mainHandler.post {
            initRecognizer()
        }
    }

    private fun muteSystemBeeps(mute: Boolean) {
        try {
            audioManager?.let { am ->
                if (mute) {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                } else {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Mute adjust note: ${e.message}")
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun getSelectedTier(): String {
        val prefs = context.getSharedPreferences("voiceflow_settings", Context.MODE_PRIVATE)
        return prefs.getString("selected_tier", "tier1") ?: "tier1"
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.i(TAG, "Recognizer ready for speech")
                        isRecognizerListening = true
                        consecutiveErrors = 0
                        startWatchdog()
                        if (isSessionActive) {
                            onStateChanged?.invoke(DictationState.LISTENING)
                        }
                    }

                    override fun onBeginningOfSpeech() {
                        Log.i(TAG, "Speech detected")
                        startWatchdog()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.15f, 1f)
                        onAmplitudeUpdated?.invoke(normalized)
                        if (rmsdB > 2.0f) {
                            // Active voice energy, extend watchdog
                            startWatchdog()
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i(TAG, "Speech pause, transcribing phrase...")
                    }

                    override fun onError(error: Int) {
                        isRecognizerListening = false
                        Log.w(TAG, "Recognition onError code: $error")

                        if (isSessionActive) {
                            consecutiveErrors++
                            if (consecutiveErrors > 6) {
                                Log.e(TAG, "Too many consecutive errors ($consecutiveErrors), finalizing session")
                                finalizeSession()
                                return
                            }
                            // 250ms backoff before reconnecting so IPC binder recovers cleanly
                            mainHandler.postDelayed({
                                if (isSessionActive) {
                                    initRecognizer()
                                    startListeningInternal()
                                }
                            }, 250)
                        } else {
                            finalizeSession()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isRecognizerListening = false
                        consecutiveErrors = 0
                        stopWatchdog()

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.i(TAG, "Segment transcription: $text")

                        if (text.isNotBlank()) {
                            sessionTextBuffer.append(text.trim()).append(" ")
                            onTranscriptionComplete?.invoke(text.trim() + " ")
                        }
                        pendingPartial = null

                        if (isSessionActive) {
                            onStateChanged?.invoke(DictationState.LISTENING)
                            mainHandler.postDelayed({
                                if (isSessionActive) {
                                    startListeningInternal()
                                }
                            }, 50)
                        } else {
                            finalizeSession()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            Log.d(TAG, "Live partial: $partial")
                            pendingPartial = partial
                            startWatchdog() // Keep session alive during continuous speech
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}", e)
        }
    }

    fun startListening() {
        mainHandler.post {
            consecutiveErrors = 0
            isFinalizing = false
            pendingPartial = null
            sessionTextBuffer.clear()
            isSessionActive = true
            muteSystemBeeps(true)
            onStateChanged?.invoke(DictationState.LISTENING)
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (!isSessionActive) return
        if (isRecognizerListening) return

        startWatchdog()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
        }

        try {
            if (speechRecognizer == null) {
                initRecognizer()
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Start listening error: ${e.message}")
            mainHandler.postDelayed({
                if (isSessionActive) {
                    initRecognizer()
                    startListeningInternal()
                }
            }, 150)
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!isSessionActive) {
                if (!isFinalizing) forceReset()
                return@post
            }
            isSessionActive = false
            onStateChanged?.invoke(DictationState.PROCESSING)

            if (isRecognizerListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.w(TAG, "Stop listening error: ${e.message}")
                }
                // 350ms grace period for SpeechRecognizer to emit onResults.
                // If it fails to emit (e.g. user was silent), finalizeSession() runs without hanging.
                mainHandler.postDelayed({
                    finalizeSession()
                }, 350L)
            } else {
                finalizeSession()
            }
        }
    }

    private fun finalizeSession() {
        if (isFinalizing) return
        isFinalizing = true
        stopWatchdog()
        muteSystemBeeps(false)

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isRecognizerListening = false

        // Append any trailing speech partial that was not yet committed by onResults
        pendingPartial?.takeIf { it.isNotBlank() }?.let { trailing ->
            val cur = sessionTextBuffer.toString()
            if (!cur.endsWith(trailing.trim() + " ")) {
                sessionTextBuffer.append(trailing.trim()).append(" ")
            }
            pendingPartial = null
        }

        val rawDraft = sessionTextBuffer.toString().trim()
        sessionTextBuffer.clear()

        if (rawDraft.isNotBlank()) {
            val tier = getSelectedTier()
            when (tier) {
                "tier1" -> {
                    Log.i(TAG, "Dispatching draft of ${rawDraft.length} chars to OfflinePunctuatorEngine (Tier 1): '$rawDraft'")
                    onStateChanged?.invoke(DictationState.PROCESSING)
                    CoroutineScope(Dispatchers.IO).launch {
                        val polished = try {
                            OfflinePunctuatorEngine.punctuate(rawDraft)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Punctuation error safely caught: ${t.message}", t)
                            rawDraft
                        }
                        deliverPolished(rawDraft, polished)
                    }
                }
                "tier2" -> {
                    Log.i(TAG, "Dispatching draft of ${rawDraft.length} chars to CloudLlmEngine (Tier 2): '$rawDraft'")
                    onStateChanged?.invoke(DictationState.PROCESSING)
                    CoroutineScope(Dispatchers.IO).launch {
                        val polished = try {
                            CloudLlmEngine.polish(context, rawDraft)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Cloud LLM error safely caught: ${t.message}", t)
                            OfflinePunctuatorEngine.punctuate(rawDraft)
                        }
                        deliverPolished(rawDraft, polished)
                    }
                }
                else -> {
                    Log.i(TAG, "Tier 3 Direct STT mode (raw speech passthrough) - injecting draft of ${rawDraft.length} chars: '$rawDraft'")
                    deliverPolished(rawDraft, rawDraft)
                }
            }
        } else {
            Log.i(TAG, "Empty draft buffer, resetting to idle")
            isFinalizing = false
            onStateChanged?.invoke(DictationState.IDLE)
        }
    }

    private fun deliverPolished(rawDraft: String, polished: String) {
        mainHandler.post {
            try {
                onSessionPolished?.invoke(rawDraft, polished)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed applying polished draft: ${t.message}", t)
            }
            isFinalizing = false
            onStateChanged?.invoke(DictationState.SUCCESS)
            mainHandler.postDelayed({
                onStateChanged?.invoke(DictationState.IDLE)
            }, 800)
        }
    }

    fun forceReset() {
        mainHandler.post {
            stopWatchdog()
            isSessionActive = false
            isRecognizerListening = false
            isFinalizing = false
            pendingPartial = null
            consecutiveErrors = 0
            muteSystemBeeps(false)
            sessionTextBuffer.clear()
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Cancel error: ${e.message}")
            }
            onStateChanged?.invoke(DictationState.IDLE)
        }
    }

    private fun restartRecognitionLoop() {
        mainHandler.post {
            if (!isSessionActive) return@post
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            isRecognizerListening = false
            initRecognizer()
            startListeningInternal()
        }
    }

    fun release() {
        mainHandler.post {
            stopWatchdog()
            isSessionActive = false
            isRecognizerListening = false
            muteSystemBeeps(false)
            sessionTextBuffer.clear()
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "Destroy error: ${e.message}")
            }
        }
    }
}
