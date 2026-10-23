package com.voiceflow.local.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voiceflow.local.engine.DictationState
import com.voiceflow.local.engine.SpeechEngine
import com.voiceflow.local.engine.OfflinePunctuatorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.voiceflow.local.theme.*

enum class DockEdge {
    LEFT,
    RIGHT
}

class DraggableFrameLayout(context: Context) : FrameLayout(context) {
    var onDragStart: (() -> Unit)? = null
    var onDragDelta: ((Float, Float) -> Unit)? = null
    var onDragRelease: (() -> Unit)? = null

    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.rawX
                initialY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.rawX - initialX)
                val dy = Math.abs(ev.rawY - initialY)
                if (dx > touchSlop || dy > touchSlop) {
                    isDragging = true
                    onDragStart?.invoke()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = ev.rawX - initialX
                    val dy = ev.rawY - initialY
                    initialX = ev.rawX
                    initialY = ev.rawY
                    onDragDelta?.invoke(dx, dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragRelease?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}

class OverlayPillService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayPillService"
        private const val NOTIFICATION_CHANNEL_ID = "voiceflow_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AUTO_COLLAPSE_DELAY_MS = 5000L
        @Volatile var isRunning = false
            private set
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val appViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = appViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayLayout: DraggableFrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var speechEngine: SpeechEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentDictationState by mutableStateOf(DictationState.IDLE)
    private var currentAmplitude by mutableFloatStateOf(0f)
    private var isCollapsed by mutableStateOf(false)
    private var currentDockEdge by mutableStateOf(DockEdge.LEFT)

    private val autoCollapseRunnable = Runnable {
        if (currentDictationState == DictationState.IDLE && !isCollapsed) {
            collapseToEdge()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundService()
        initSpeechEngine()
        CoroutineScope(Dispatchers.IO).launch {
            OfflinePunctuatorEngine.initialize(this@OverlayPillService)
        }
        showOverlayPill()
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        // STRICT LOCK: Never post auto-collapse timer while dictating or processing
        if (currentDictationState == DictationState.IDLE && !isCollapsed) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS)
        }
    }

    private fun cancelInactivityTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun expandFromEdge() {
        cancelInactivityTimer()
        isCollapsed = false
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val pillWidthPx = (175 * density).toInt()

        windowParams?.let { params ->
            val targetX = if (currentDockEdge == DockEdge.LEFT) {
                16
            } else {
                screenWidth - pillWidthPx - 16
            }
            animateWindowX(params, targetX)
        }
        if (currentDictationState == DictationState.IDLE) {
            resetInactivityTimer()
        }
    }

    private fun collapseToEdge() {
        // STRICT LOCK: Suppress any collapse if user is actively dictating or processing
        if (currentDictationState != DictationState.IDLE) {
            Log.d(TAG, "Suppressed collapse: session is active ($currentDictationState)")
            return
        }
        cancelInactivityTimer()
        isCollapsed = true
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val tabWidthPx = (32 * density).toInt()

        windowParams?.let { params ->
            val targetX = if (currentDockEdge == DockEdge.LEFT) {
                0
            } else {
                screenWidth - tabWidthPx
            }
            animateWindowX(params, targetX)
        }
    }

    private fun animateWindowX(params: WindowManager.LayoutParams, targetX: Int) {
        val animator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                params.x = va.animatedValue as Int
                overlayLayout?.let { frame ->
                    try {
                        windowManager.updateViewLayout(frame, params)
                    } catch (_: Exception) {}
                }
            }
        }
        animator.start()
    }

    private fun initSpeechEngine() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WriteMyWords:DictationAwake"
        )

        speechEngine = SpeechEngine(this).apply {
            onTranscriptionComplete = { transcript ->
                // Clean buffer strategy: Do NOT write draft text to the screen while speaking.
                // Keep the input area clean until polished text is ready.
            }
            onSessionPolished = { rawDraft, polished ->
                val textToInject = if (polished.isNotBlank()) polished.trim() + " " else rawDraft.trim() + " "
                DictationAccessibilityService.injectTextGlobally(this@OverlayPillService, textToInject)
            }
            onStateChanged = { state ->
                currentDictationState = state
                handleScreenAwakeState(state)
                if (state == DictationState.IDLE) {
                    resetInactivityTimer()
                } else {
                    // Lock the pill solid: strictly cancel any auto-collapse
                    cancelInactivityTimer()
                    if (isCollapsed) {
                        expandFromEdge()
                    }
                }
            }
            onAmplitudeUpdated = { amp ->
                currentAmplitude = amp
            }
        }
    }

    private fun handleScreenAwakeState(state: DictationState) {
        try {
            if (state == DictationState.LISTENING) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
                overlayLayout?.let { frame ->
                    windowParams?.let { params ->
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        windowManager.updateViewLayout(frame, params)
                    }
                }
            } else if (state == DictationState.IDLE) {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                overlayLayout?.let { frame ->
                    windowParams?.let { params ->
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                        windowManager.updateViewLayout(frame, params)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Screen awake update note: ${e.message}")
        }
    }

    private fun startForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "WriteMyWords Floating Overlay",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active voice dictation floating capsule"
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("WriteMyWords")
                .setContentText("Local dictation pill active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service notice: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlayPill() {
        val screenWidth = resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 350
        }
        windowParams = params

        val frame = DraggableFrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@OverlayPillService)
            setViewTreeSavedStateRegistryOwner(this@OverlayPillService)
            setViewTreeViewModelStoreOwner(this@OverlayPillService)

            onDragStart = {
                cancelInactivityTimer()
                if (isCollapsed) {
                    isCollapsed = false
                }
            }

            onDragDelta = { dx, dy ->
                params.x = (params.x + dx).toInt().coerceIn(0, screenWidth - 80)
                params.y = (params.y + dy).toInt().coerceIn(80, resources.displayMetrics.heightPixels - 200)
                try {
                    windowManager.updateViewLayout(this, params)
                } catch (e: Exception) {
                    Log.d(TAG, "Layout drag update: ${e.message}")
                }
            }

            onDragRelease = {
                snapToNearestEdge(params, this)
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayPillService)
            setViewTreeSavedStateRegistryOwner(this@OverlayPillService)
            setViewTreeViewModelStoreOwner(this@OverlayPillService)
            setContent {
                WriteMyWordsTheme {
                    PillCapsuleOverlay(
                        currentState = currentDictationState,
                        rawAmp = currentAmplitude,
                        isCollapsed = isCollapsed,
                        dockEdge = currentDockEdge,
                        onStartListening = { speechEngine?.startListening() },
                        onStopListening = { speechEngine?.stopListening() },
                        onForceReset = { speechEngine?.forceReset() },
                        onExpandRequest = { expandFromEdge() },
                        onCollapseRequest = { collapseToEdge() },
                        onUserInteraction = {
                            if (currentDictationState == DictationState.IDLE) {
                                resetInactivityTimer()
                            } else {
                                cancelInactivityTimer()
                            }
                        }
                    )
                }
            }
        }

        frame.addView(composeView)
        overlayLayout = frame
        windowManager.addView(frame, params)
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams, frame: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val pillWidth = frame.width.takeIf { it > 0 } ?: (175 * resources.displayMetrics.density).toInt()
        val midPoint = screenWidth / 2
        val currentCenter = params.x + (pillWidth / 2)

        if (currentCenter < midPoint) {
            currentDockEdge = DockEdge.LEFT
            val targetX = 16
            animateWindowX(params, targetX)
        } else {
            currentDockEdge = DockEdge.RIGHT
            val targetX = screenWidth - pillWidth - 16
            animateWindowX(params, targetX)
        }
        if (currentDictationState == DictationState.IDLE) {
            resetInactivityTimer()
        } else {
            cancelInactivityTimer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelInactivityTimer()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        appViewModelStore.clear()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        speechEngine?.release()
        speechEngine = null

        overlayLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
        }
        overlayLayout = null
    }
}

@Composable
fun PillCapsuleOverlay(
    currentState: DictationState,
    rawAmp: Float,
    isCollapsed: Boolean,
    dockEdge: DockEdge,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onForceReset: () -> Unit,
    onExpandRequest: () -> Unit,
    onCollapseRequest: () -> Unit,
    onUserInteraction: () -> Unit
) {
    val smoothedAmp by animateFloatAsState(
        targetValue = rawAmp.coerceIn(0.15f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "amplitudeSmooth"
    )

    if (isCollapsed) {
        // --- Option B: Edge-Tucking Slim Tab ---
        val tabShape = if (dockEdge == DockEdge.LEFT) {
            RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
        }

        Box(
            modifier = Modifier
                .width(32.dp)
                .height(44.dp)
                .clip(tabShape)
                .background(ObsidianSurfaceContainer.copy(alpha = 0.85f))
                .border(1.5.dp, ElectricCyan.copy(alpha = 0.7f), tabShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            onExpandRequest()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Expand Dictation Pill",
                tint = ElectricCyan,
                modifier = Modifier.size(17.dp)
            )
        }
    } else {
        // --- Expanded Floating Pill with Double-Tap / Long-Press Minimize ---
        Box(
            modifier = Modifier
                .width(175.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(ObsidianSurfaceContainer.copy(alpha = 0.95f))
                .border(
                    1.5.dp,
                    when (currentState) {
                        DictationState.IDLE -> BorderOutline
                        DictationState.LISTENING -> ElectricCyan
                        DictationState.PROCESSING -> RadiantViolet
                        DictationState.SUCCESS -> EmeraldEdge
                    },
                    RoundedCornerShape(50)
                )
                .pointerInput(currentState) {
                    detectTapGestures(
                        onTap = {
                            onUserInteraction()
                            when (currentState) {
                                DictationState.IDLE -> onStartListening()
                                DictationState.LISTENING -> onStopListening()
                                DictationState.PROCESSING -> onForceReset()
                                DictationState.SUCCESS -> onForceReset()
                            }
                        },
                        onDoubleTap = {
                            if (currentState == DictationState.IDLE) {
                                onCollapseRequest()
                            }
                        },
                        onLongPress = {
                            if (currentState == DictationState.IDLE) {
                                onCollapseRequest()
                            }
                        }
                    )
                }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            when (currentState) {
                DictationState.IDLE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Tap to Dictate",
                            tint = ElectricCyan,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = "Tap to Speak",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
                DictationState.LISTENING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(ErrorRed.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.5.dp)
                                    .clip(CircleShape)
                                    .background(ErrorRed)
                            )
                        }

                        // Equalizer Waveform bars
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                            modifier = Modifier.height(16.dp)
                        ) {
                            val multipliers = listOf(0.6f, 1.0f, 0.45f, 0.85f, 0.55f)
                            multipliers.forEachIndexed { index, mult ->
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .height(14.dp)
                                        .graphicsLayer {
                                            scaleY = (smoothedAmp * mult).coerceIn(0.25f, 1.2f)
                                        }
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (index % 2 == 0) ElectricCyan else RadiantViolet)
                                )
                            }
                        }

                        Text(
                            text = "Listening",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricCyan
                        )
                    }
                }
                DictationState.PROCESSING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = RadiantViolet,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Structuring...",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                    }
                }
                DictationState.SUCCESS -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = EmeraldEdge,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Injected",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldEdge
                        )
                    }
                }
            }
        }
    }
}
