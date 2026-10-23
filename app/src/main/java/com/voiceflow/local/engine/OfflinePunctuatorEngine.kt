package com.voiceflow.local.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object OfflinePunctuatorEngine {
    private const val TAG = "OfflinePunctuator"
    private const val ASSET_MODEL_NAME = "model.int8.onnx"
    private const val ASSET_VOCAB_NAME = "bpe.vocab"
    private const val ASSET_SUBDIR = "punct"

    @Volatile
    private var onlinePunctuation: OnlinePunctuation? = null

    @Volatile
    var isModelLoaded: Boolean = false
        private set

    @Volatile
    private var isInitializing: Boolean = false

    const val MODEL_DISPLAY_NAME: String = "Sherpa-ONNX Punct-EN (7.5MB INT8)"

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true
        if (isInitializing) return@withContext false

        isInitializing = true
        try {
            val modelsDir = File(context.filesDir, "models/$ASSET_SUBDIR").apply { mkdirs() }
            val modelFile = File(modelsDir, ASSET_MODEL_NAME)
            val vocabFile = File(modelsDir, ASSET_VOCAB_NAME)

            extractAssetIfNeeded(context, "$ASSET_SUBDIR/$ASSET_MODEL_NAME", modelFile)
            extractAssetIfNeeded(context, "$ASSET_SUBDIR/$ASSET_VOCAB_NAME", vocabFile)

            if (!modelFile.exists() || !vocabFile.exists()) {
                Log.e(TAG, "Failed to extract punctuation model assets to ${modelsDir.absolutePath}")
                isInitializing = false
                return@withContext false
            }

            Log.i(TAG, "Initializing Sherpa-ONNX OnlinePunctuation from: ${modelFile.absolutePath}")
            val modelConfig = OnlinePunctuationModelConfig(
                cnnBilstm = modelFile.absolutePath,
                bpeVocab = vocabFile.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = OnlinePunctuationConfig(model = modelConfig)
            onlinePunctuation = OnlinePunctuation(null, config)
            isModelLoaded = true
            Log.i(TAG, "OfflinePunctuatorEngine successfully initialized and ready!")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing OfflinePunctuatorEngine: ${t.message}", t)
            onlinePunctuation = null
            isModelLoaded = false
            false
        } finally {
            isInitializing = false
        }
    }

    private fun extractAssetIfNeeded(context: Context, assetPath: String, destFile: File) {
        try {
            val assetManager = context.assets
            val assetFd = try { assetManager.openFd(assetPath) } catch (_: Exception) { null }
            val expectedLength = assetFd?.length ?: -1L
            assetFd?.close()

            if (destFile.exists()) {
                if (expectedLength > 0 && destFile.length() == expectedLength) {
                    return // already extracted and matches size
                } else if (expectedLength <= 0 && destFile.length() > 0) {
                    return // exists
                }
            }

            Log.i(TAG, "Extracting asset '$assetPath' -> '${destFile.absolutePath}'")
            assetManager.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting asset $assetPath: ${e.message}", e)
        }
    }

    suspend fun punctuate(rawText: String): String = withContext(Dispatchers.Default) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return@withContext ""

        val engine = onlinePunctuation
        if (engine == null || !isModelLoaded) {
            Log.w(TAG, "Punctuator not initialized, returning raw transcript")
            return@withContext trimmed
        }

        try {
            val t0 = System.currentTimeMillis()
            var result = engine.addPunctuation(trimmed).trim()
            val dt = System.currentTimeMillis() - t0

            if (result.isNotEmpty()) {
                result = result.replaceFirstChar { it.uppercase() }
            }

            Log.i(TAG, "Offline punctuation applied in ${dt}ms: '$result'")
            if (result.isNotBlank()) result else trimmed
        } catch (t: Throwable) {
            Log.e(TAG, "Punctuation error: ${t.message}", t)
            trimmed
        }
    }

    suspend fun structureAndPunctuate(rawText: String): String = punctuate(rawText)

    fun release() {
        try {
            onlinePunctuation?.release()
            onlinePunctuation = null
            isModelLoaded = false
            Log.i(TAG, "OfflinePunctuatorEngine released.")
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing OfflinePunctuatorEngine: ${t.message}")
        }
    }
}
