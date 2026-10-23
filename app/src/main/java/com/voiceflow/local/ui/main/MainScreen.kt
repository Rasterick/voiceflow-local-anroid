package com.voiceflow.local.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.voiceflow.local.data.VocabularyManager
import com.voiceflow.local.data.VocabularyProfile
import com.voiceflow.local.service.DictationAccessibilityService
import com.voiceflow.local.engine.OfflinePunctuatorEngine
import com.voiceflow.local.engine.CloudLlmEngine
import com.voiceflow.local.engine.CloudProvider
import com.voiceflow.local.engine.CloudFormatMode
import kotlinx.coroutines.launch
import com.voiceflow.local.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isOverlayRunning: Boolean,
    hasMicPermission: Boolean = false,
    onRequestMicPermission: () -> Unit = {},
    onToggleOverlay: () -> Unit
) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkAccessibility(): Boolean {
        if (isInspection) return false
        val isRunning = try { DictationAccessibilityService.isRunning() } catch (_: Throwable) { false }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isEnabled = enabledServices?.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        } == true
        val enabledStr = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (_: Throwable) { "" }
        val inSettings = enabledStr.contains(context.packageName)
        android.util.Log.i("ACC_DEBUG", "isRunning=$isRunning, isEnabled=$isEnabled, inSettings=$inSettings, str=$enabledStr")
        return isRunning || isEnabled || inSettings
    }

    var hasOverlayPermission by remember {
        mutableStateOf(if (isInspection) true else Settings.canDrawOverlays(context))
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(if (isInspection) false else checkAccessibility())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!isInspection) {
                    hasOverlayPermission = Settings.canDrawOverlays(context)
                    isAccessibilityEnabled = checkAccessibility()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val prefs = remember { context.getSharedPreferences("voiceflow_settings", Context.MODE_PRIVATE) }
    var selectedTier by remember { mutableStateOf(prefs.getString("selected_tier", "tier1") ?: "tier1") }
    var cloudProvider by remember { mutableStateOf(CloudLlmEngine.getProvider(context)) }
    var cloudApiKey by remember { mutableStateOf(CloudLlmEngine.getApiKey(context)) }
    var cloudFormatMode by remember { mutableStateOf(CloudLlmEngine.getFormatMode(context)) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var testStatusText by remember { mutableStateOf<String?>(null) }
    var isTestingKey by remember { mutableStateOf(false) }

    var useSystemDictionary by remember { mutableStateOf(true) }
    var selectedProfileId by remember { mutableStateOf("software_dev") }

    val profiles = remember { VocabularyManager.defaultProfiles }
    val systemWordsCount = remember {
        if (useSystemDictionary && !isInspection) {
            try {
                VocabularyManager.readSystemUserDictionary(context).size
            } catch (_: Throwable) {
                0
            }
        } else 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ElectricCyan)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "WriteMyWords",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ObsidianSurfaceHigh)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "LOCAL CORE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ElectricCyan
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianBg
                )
            )
        },
        containerColor = ObsidianBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Status Banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, BorderOutline, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "System Telemetry",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(ObsidianSurfaceHigh)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isOverlayRunning) EmeraldEdge else TextMuted)
                                    )
                                    Text(
                                        text = if (isOverlayRunning) "OVERLAY ACTIVE" else "IDLE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOverlayRunning) EmeraldEdge else TextMuted
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Universal voice dictation with zero cloud egress. Runs completely on-device.",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                if (!hasOverlayPermission) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    onToggleOverlay()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOverlayRunning) ErrorContainer else ElectricCyanContainer,
                                contentColor = if (isOverlayRunning) ErrorRed else OnElectricCyan
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (isOverlayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (!hasOverlayPermission) "Grant Overlay Permission First"
                                else if (isOverlayRunning) "Hide Floating Pill Overlay"
                                else "Launch Floating Pill Overlay",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Permissions Dashboard
            item {
                Text(
                    text = "System Permissions",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, BorderOutline, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Microphone Permission Row
                    PermissionRow(
                        title = "Microphone Access",
                        subtitle = "Required for on-device voice capture",
                        isGranted = hasMicPermission || isInspection,
                        onAction = onRequestMicPermission
                    )

                    HorizontalDivider(color = BorderOutline)

                    // Overlay Permission Row
                    PermissionRow(
                        title = "Display Over Other Apps",
                        subtitle = "Required for the floating pill capsule",
                        isGranted = hasOverlayPermission,
                        onAction = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = BorderOutline)

                    // Accessibility Permission Row
                    PermissionRow(
                        title = "Accessibility Text Injection",
                        subtitle = "Allows typing directly into WhatsApp, Gmail, Search",
                        isGranted = isAccessibilityEnabled,
                        onAction = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )
                }
            }

            // 3. Processing Engine (3 Tiers)
            item {
                Text(
                    text = "Processing Engine (Local-First Priority)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Tier 1: Offline Punctuator (Default)
                    val isPunctReady = OfflinePunctuatorEngine.isModelLoaded
                    EngineTierCard(
                        title = "Tier 1: Offline Punctuator (Local Core)",
                        badge = if (isPunctReady) "DEFAULT • 100% OFFLINE (14ms)" else "READY ON DEVICE",
                        description = "Instant neural punctuation & capitalization. Zero latency, zero cloud egress, 100% private.",
                        badgeColor = EmeraldEdge,
                        isSelected = selectedTier == "tier1",
                        onClick = { selectedTier = "tier1"; prefs.edit().putString("selected_tier", "tier1").apply() }
                    )

                    // Tier 2: Cloud LLM
                    val hasKey = cloudApiKey.isNotBlank()
                    EngineTierCard(
                        title = "Tier 2: Cloud Intelligence (${cloudProvider.displayName})",
                        badge = if (hasKey) "ACTIVE • BYO KEY" else "NEEDS API KEY",
                        description = "Prose restructuring, bullet points, and grammar cleanup powered by Gemini 1.5 Flash or Groq (~300ms).",
                        badgeColor = RadiantViolet,
                        isSelected = selectedTier == "tier2",
                        onClick = { selectedTier = "tier2"; prefs.edit().putString("selected_tier", "tier2").apply() }
                    )

                    // Tier 3: Direct Raw STT (Passthrough / Diagnostics)
                    EngineTierCard(
                        title = "Tier 3: Raw Android STT (Diagnostics)",
                        badge = "PASSTHROUGH • NO CLEANUP",
                        description = "Direct verbatim speech capture with zero neural post-processing. Useful for baseline testing.",
                        badgeColor = BorderOutline,
                        isSelected = selectedTier == "tier3",
                        onClick = { selectedTier = "tier3"; prefs.edit().putString("selected_tier", "tier3").apply() }
                    )
                }
            }

            // Cloud LLM Configuration Box (when Tier 2 is selected)
            if (selectedTier == "tier2") {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianSurface)
                            .border(1.dp, RadiantViolet.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cloud LLM Configuration",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "BYO KEY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = RadiantViolet
                            )
                        }

                        // Provider selection chips
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CloudProvider.entries.forEach { provider ->
                                val isSelected = cloudProvider == provider
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) RadiantViolet.copy(alpha = 0.2f) else ObsidianSurfaceHigh)
                                        .border(1.dp, if (isSelected) RadiantViolet else BorderOutline, RoundedCornerShape(8.dp))
                                        .clickable {
                                            cloudProvider = provider
                                            CloudLlmEngine.setProvider(context, provider)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else TextSecondary
                                    )
                                }
                            }
                        }

                        // Formatting mode selection chips
                        Text(
                            text = "Formatting Style:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CloudFormatMode.entries.forEach { mode ->
                                val isSelected = cloudFormatMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) ElectricCyan.copy(alpha = 0.15f) else ObsidianSurfaceHigh)
                                        .border(1.dp, if (isSelected) ElectricCyan else BorderOutline, RoundedCornerShape(6.dp))
                                        .clickable {
                                            cloudFormatMode = mode
                                            CloudLlmEngine.setFormatMode(context, mode)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = mode.displayName,
                                        fontSize = 11.sp,
                                        color = if (isSelected) ElectricCyan else TextMuted
                                    )
                                }
                            }
                        }

                        // API Key Input
                        OutlinedTextField(
                            value = cloudApiKey,
                            onValueChange = {
                                cloudApiKey = it
                                CloudLlmEngine.setApiKey(context, it)
                            },
                            label = { Text("${cloudProvider.displayName} API Key", fontSize = 12.sp) },
                            placeholder = { Text(if (cloudProvider == CloudProvider.GEMINI) "AIzaSy..." else "gsk_...", fontSize = 12.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                    Icon(
                                        imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle key visibility",
                                        tint = TextMuted
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadiantViolet,
                                unfocusedBorderColor = BorderOutline,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = RadiantViolet,
                                unfocusedLabelColor = TextMuted
                            )
                        )

                        // Test Key Row
                        val scope = rememberCoroutineScope()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            testStatusText?.let { status ->
                                Text(
                                    text = status,
                                    fontSize = 11.sp,
                                    color = if (status.startsWith("✓")) EmeraldEdge else Color(0xFFFF6B6B),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                            }
                            Button(
                                onClick = {
                                    if (cloudApiKey.isBlank()) {
                                        testStatusText = "Please enter an API key"
                                        return@Button
                                    }
                                    isTestingKey = true
                                    testStatusText = "Testing API connection..."
                                    scope.launch {
                                        try {
                                            val result = CloudLlmEngine.testConnection(context, cloudApiKey, cloudProvider)
                                            testStatusText = "✓ Verified: '$result'"
                                        } catch (e: Exception) {
                                            testStatusText = "Error: ${e.message}"
                                        } finally {
                                            isTestingKey = false
                                        }
                                    }
                                },
                                enabled = !isTestingKey,
                                colors = ButtonDefaults.buttonColors(containerColor = RadiantViolet),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isTestingKey) "Testing..." else "Test Key",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // 4. Vocabulary Profiles & System Dictionary
            item {
                Text(
                    text = "Domain Vocabulary & Dictionary",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, BorderOutline, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Android User Dictionary",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = "$systemWordsCount custom words loaded from your phone keyboard",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                        Switch(
                            checked = useSystemDictionary,
                            onCheckedChange = { useSystemDictionary = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ObsidianBg,
                                checkedTrackColor = ElectricCyan
                            )
                        )
                    }

                    HorizontalDivider(color = BorderOutline)

                    Text(
                        text = "Active Vocabulary Profile:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(profiles) { profile ->
                            val isSelected = profile.id == selectedProfileId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) ElectricCyanContainer else ObsidianSurfaceHigh)
                                    .clickable { selectedProfileId = profile.id }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = profile.name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) OnElectricCyan else TextPrimary
                                )
                            }
                        }
                    }

                    val currentProfile = profiles.find { it.id == selectedProfileId }
                    if (currentProfile != null && currentProfile.keywords.isNotEmpty()) {
                        Text(
                            text = "Domain Keywords Biased:",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            currentProfile.keywords.forEach { keyword ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ObsidianSurfaceLow)
                                        .border(1.dp, BorderOutline, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = keyword,
                                        fontSize = 11.sp,
                                        color = ElectricCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) EmeraldEdge else ErrorRed,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text(text = subtitle, fontSize = 12.sp, color = TextMuted)
            }
        }
        TextButton(onClick = onAction) {
            Text(
                text = if (isGranted) "Granted" else "Grant",
                color = if (isGranted) EmeraldEdge else ElectricCyan,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun EngineTierCard(
    title: String,
    badge: String,
    description: String,
    badgeColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) ObsidianSurfaceHigh else ObsidianSurface)
            .border(
                1.dp,
                if (isSelected) ElectricCyan else BorderOutline,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0F131C)
@Composable
fun MainScreenPreview() {
    WriteMyWordsTheme {
        MainScreen(
            isOverlayRunning = false,
            onToggleOverlay = {}
        )
    }
}
