package com.voiceflow.local.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DictationAccService"
        var instance: WeakReference<DictationAccessibilityService>? = null
            private set

        fun isRunning(): Boolean = instance?.get() != null

        fun injectTextGlobally(context: Context, text: String): Boolean {
            val service = instance?.get()
            if (service == null) {
                Log.w(TAG, "Accessibility Service not connected, copying to clipboard")
                copyToClipboard(context, text)
                return false
            }
            return service.injectText(text)
        }

        fun copyToClipboard(context: Context, text: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("WriteMyWords", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.e(TAG, "Failed copying to clipboard: ${e.message}")
            }
        }
    }

    // Anchor context tracking across apps
    private var lastAnchoredPackage: String? = null
    private var lastAnchoredWindowId: Int = -1
    private var lastAnchoredViewId: String? = null
    private var lastAnchoredClassName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        Log.i(TAG, "Dictation Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore events from our own overlay pill service to avoid losing the user's active app anchor
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    lastAnchoredPackage = pkg
                    lastAnchoredWindowId = event.windowId
                    lastAnchoredViewId = source.viewIdResourceName
                    lastAnchoredClassName = source.className?.toString()
                    Log.d(TAG, "Anchored editable target: pkg=$pkg, viewId=$lastAnchoredViewId, class=$lastAnchoredClassName")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != packageName) {
                    lastAnchoredPackage = pkg
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Dictation Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Locates the active editable text input across all windows, prioritizing the user's anchored app.
     */
    fun findActiveEditableNode(): AccessibilityNodeInfo? {
        val windowList = try { windows } catch (e: Exception) { null }
        if (!windowList.isNullOrEmpty()) {
            val appWindows = windowList.filter { 
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION 
            }

            // Prioritize the window matching our last anchored package (e.g. Gmail com.google.android.gm)
            val sortedWindows = if (lastAnchoredPackage != null) {
                appWindows.sortedByDescending { it.root?.packageName?.toString() == lastAnchoredPackage }
            } else {
                appWindows
            }

            for (window in sortedWindows) {
                val root = window.root ?: continue

                // 1. Check window's input focus (most accurate for active cursor in third-party apps)
                val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedInput != null && focusedInput.isEditable) {
                    Log.i(TAG, "Found target via window.findFocus(FOCUS_INPUT) in ${root.packageName}")
                    return focusedInput
                }

                // 2. Check by viewId if previously anchored
                if (!lastAnchoredViewId.isNullOrEmpty()) {
                    val matchingNodes = root.findAccessibilityNodeInfosByViewId(lastAnchoredViewId!!)
                    val match = matchingNodes?.firstOrNull { it.isEditable }
                    if (match != null) {
                        Log.i(TAG, "Found target via anchored viewId: $lastAnchoredViewId in ${root.packageName}")
                        return match
                    }
                }

                // 3. Recursive search for focused editable node
                val focusedEditable = findFocusedEditableRecursively(root)
                if (focusedEditable != null) {
                    Log.i(TAG, "Found focused editable recursively in ${root.packageName}")
                    return focusedEditable
                }

                // 4. Recursive search for any editable node in this app window
                val anyEditable = findAnyEditableRecursively(root)
                if (anyEditable != null) {
                    Log.i(TAG, "Found candidate editable node in ${root.packageName}")
                    return anyEditable
                }
            }
        }

        // Fallback to rootInActiveWindow
        rootInActiveWindow?.let { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                if (focused.isEditable) {
                    Log.i(TAG, "Found target via rootInActiveWindow.findFocus(FOCUS_INPUT)")
                    return focused
                }
            }
            findFocusedEditableRecursively(root)?.let { return it }
            findAnyEditableRecursively(root)?.let { return it }
        }

        return null
    }

    private fun findFocusedEditableRecursively(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableRecursively(child)
            if (found != null) return found
        }
        return null
    }

    private fun findAnyEditableRecursively(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        // Check children from bottom to top (compose boxes are typically lower down in view hierarchies)
        for (i in (node.childCount - 1) downTo 0) {
            val child = node.getChild(i) ?: continue
            val found = findAnyEditableRecursively(child)
            if (found != null) return found
        }
        if (node.isEditable) return node
        return null
    }

    /**
     * Injects finalized text into the active target app at the current cursor position.
     */
    fun injectText(text: String): Boolean {
        // Always stage text to system clipboard so the user never loses their dictated content
        copyToClipboard(this, text)

        val targetNode = findActiveEditableNode()
        if (targetNode != null) {
            Log.i(TAG, "Injecting text into node: pkg=${targetNode.packageName}, id=${targetNode.viewIdResourceName}")

            // Anchor input focus back to the target view
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            // Primary injection method: ACTION_PASTE
            // Universal across rich-text editors (Gmail, WhatsApp, Notes, Slack, Chrome)
            val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteSuccess) {
                Log.i(TAG, "Successfully injected text via ACTION_PASTE")
                return true
            }

            // Fallback injection method: ACTION_SET_TEXT
            Log.d(TAG, "ACTION_PASTE did not succeed, attempting ACTION_SET_TEXT fallback")
            val currentText = targetNode.text?.toString() ?: ""
            val newText = if (currentText.isEmpty()) {
                text.trimStart()
            } else if (currentText.endsWith(" ") || text.startsWith(" ")) {
                currentText + text
            } else {
                "$currentText $text"
            }

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (setTextSuccess) {
                Log.i(TAG, "Successfully injected text via ACTION_SET_TEXT")
                return true
            }
        }

        Log.w(TAG, "No editable field accessible in foreground window; text safely staged in clipboard")
        return false
    }
}
