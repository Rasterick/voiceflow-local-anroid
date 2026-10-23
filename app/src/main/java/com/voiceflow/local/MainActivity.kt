package com.voiceflow.local

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.voiceflow.local.engine.OfflinePunctuatorEngine
import com.voiceflow.local.service.OverlayPillService
import com.voiceflow.local.theme.WriteMyWordsTheme
import com.voiceflow.local.ui.main.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isOverlayRunning by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            launchOverlay()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice dictation", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = android.content.IntentFilter().apply {
            addAction("com.voiceflow.local.TEST_LLM")
            addAction("com.voiceflow.local.TEST_PUNCT")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }
        checkMicPermission()
        isOverlayRunning = OverlayPillService.isRunning

        CoroutineScope(Dispatchers.IO).launch {
            OfflinePunctuatorEngine.initialize(this@MainActivity)
        }

        setContent {
            WriteMyWordsTheme {
                MainScreen(
                    isOverlayRunning = isOverlayRunning,
                    hasMicPermission = hasMicPermission,
                    onRequestMicPermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onToggleOverlay = {
                        if (isOverlayRunning) {
                            stopOverlay()
                        } else {
                            if (hasMicPermission) {
                                launchOverlay()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkMicPermission()
        isOverlayRunning = OverlayPillService.isRunning
    }

    private val testReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val sample = intent?.getStringExtra("text_b64")?.let {
                try { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (_: Throwable) { null }
            } ?: intent?.getStringExtra("text") ?: "item number one speak clearly item number two brushed teeth twice a day"

            if (intent?.action == "com.voiceflow.local.TEST_LLM") {
                android.util.Log.i("TEST_LLM", "Received test text: '$sample'")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val out = com.voiceflow.local.engine.CloudLlmEngine.polish(this@MainActivity, sample)
                    android.util.Log.i("TEST_LLM", "LLM RESULT:\n$out")
                }
            } else {
                android.util.Log.i("TEST_PUNCT", "Received test text: '$sample'")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val out = com.voiceflow.local.engine.OfflinePunctuatorEngine.punctuate(sample)
                    android.util.Log.i("TEST_PUNCT", "PUNCTUATION SUCCESS: '$out'")
                }
            }
        }
    }

    private fun checkMicPermission() {
        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchOverlay() {
        val intent = Intent(this, OverlayPillService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isOverlayRunning = true
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayPillService::class.java)
        stopService(intent)
        isOverlayRunning = false
    }
}
