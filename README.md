# WriteMyWords (VoiceFlow Local Android)

A high-performance, private voice dictation and prose structuring app for Android with a floating pill overlay that types directly into any text field across your device.

---

## Architecture & Tiers

### 🚀 Tier 1: Offline Punctuator (Default Flagship)
- **100% On-Device:** Powered by Sherpa-ONNX INT8 CNN-BiLSTM (~7.5MB model bundled in assets).
- **Zero Cloud Egress:** Operates completely offline with full privacy.
- **Ultra-Low Latency:** ~14ms execution on device.
- **Features:** Automatic sentence boundary detection, commas, periods, question marks, and capitalization. Zero RAM drain when idle.

### ⚡ Tier 2: Cloud Intelligence (Opt-in Upgrade)
- **BYO API Key:** Full support for Google Gemini (Flash) and Groq (Llama 3.1 Instant).
- **Intelligent Formatting Styles:**
  - **Clean & Natural:** Removes filler words (*um*, *uh*), corrects mishearings, and polishes punctuation while preserving personal voice.
  - **Professional Message:** Rewrites spoken dictation into formal email prose and repairs speech acoustic errors/homophones (e.g., *"Fear sir"* ➔ *"Dear Sir"*, *"arrange alone"* ➔ *"arrange a loan"*, *"spendage"* ➔ *"spending"*).
  - **Action Bullets:** Formats dictation into clean, structured bullet lists starting with `• `.
- **Resilient Fallback:** If cloud quota is reached or network drops, it immediately falls back to the on-device Tier 1 Offline Punctuator without failing.

### 🔍 Tier 3: Diagnostic Direct STT
- Passthrough speech recognition for testing raw speech recognition output.

---

## Key Features

- **Universal Floating Pill:** Compact overlay draggable anywhere on the screen. Tap to dictate, tap to stop.
- **Direct Accessibility Injection:** Seamlessly pastes finalized text into active fields (Gmail, WhatsApp, Notes, Slack, etc.) without clipboard hassles.
- **Audio Ducking & System Beep Suppression:** Suppresses intrusive OS audio chimes during continuous dictation.
- **Modern Obsidian Design:** Jetpack Compose UI with dark mode, provider toggles, and live connection verification.

---

## Tech Stack

- **Platform:** Android (Kotlin)
- **UI:** Jetpack Compose with Material 3 & Custom Obsidian Design System
- **Offline ML:** Sherpa-ONNX (INT8 BiLSTM)
- **Cloud LLM:** OkHttp, Google Gemini API & Groq API
- **Services:** Android Foreground Service & AccessibilityService
