package com.gemini.live

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.ArrayList

/**
 * VolumeTriggerService.
 * CRITICAL RULES PRESERVED:
 * - Rule #1: Double-press volume down launches MainActivity with haptics (no PIN prompt).
 * - Rule #3: Candidate ID Grounding (extracts [#0], [#1]... and tap_element_id / long_press_element_id uses exact hardware Rect).
 * - Rule #5: Samsung Keyboard-Dismiss Text Replacement (GLOBAL_ACTION_BACK -> 80ms -> focus/paste).
 */
class VolumeTriggerService : AccessibilityService() {

    companion object {
        var instance: VolumeTriggerService? = null
    }

    private var lastVolumeDownTime: Long = 0
    private val DOUBLE_PRESS_INTERVAL = 450L
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cached hardware bounds & nodes for candidate element IDs (#0, #1, #2...)
    val activeCandidateBounds = ArrayList<Rect>()
    val activeCandidateNodes = ArrayList<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            autoApproveDialogs()
        }
    }

    private fun autoApproveDialogs() {
        val root = rootInActiveWindow ?: return
        findAndClickApprovalButton(root)
    }

    private fun findAndClickApprovalButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text
        if (text != null) {
            val t = text.toString().lowercase().trim()
            if (t == "start now" || t == "allow" || t == "while using the app") {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        val id = node.viewIdResourceName
        if (id != null && (id.endsWith("button1") || id.endsWith("permission_allow_button"))) {
            val btnText = node.text
            if (btnText == null || !btnText.toString().lowercase().contains("cancel")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        for (i in 0 until node.childCount) {
            if (findAndClickApprovalButton(node.getChild(i))) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - lastVolumeDownTime < DOUBLE_PRESS_INTERVAL) {
                lastVolumeDownTime = 0
                launchAssistantWithHaptics()
                return true
            } else {
                lastVolumeDownTime = now
            }
        }
        return super.onKeyEvent(event)
    }

    private fun launchAssistantWithHaptics() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 50), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 50, 70, 50), -1)
                }
            }
        } catch (ignored: Exception) {}

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
    }

    // Rule #3: Tap Element by Candidate ID with auto-feedback
    fun executeTapElementId(id: Int): String {
        if (id < 0 || id >= activeCandidateBounds.size) {
            return "Invalid element ID #$id.\n\n${readScreenText()}"
        }
        val r = activeCandidateBounds[id]
        var ok = false
        if (r.width() > 0 && r.height() > 0) {
            ok = tapCoordinates(r.centerX(), r.centerY())
        }
        SystemClock.sleep(250) // Settle time
        val status = if (ok) "Tapped element #$id successfully." else "Action failed."
        return "$status\n\n${readScreenText()}"
    }

    // Candidate Long-Press
    fun executeLongPressElementId(id: Int): String {
        if (id < 0 || id >= activeCandidateBounds.size) {
            return "Invalid element ID #$id.\n\n${readScreenText()}"
        }
        var ok = false
        if (id < activeCandidateNodes.size) {
            val node = activeCandidateNodes[id]
            if (node.isLongClickable) {
                ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
        }
        if (!ok) {
            val r = activeCandidateBounds[id]
            ok = longPress(r.centerX(), r.centerY())
        }
        SystemClock.sleep(300)
        val status = if (ok) "Long-pressed element #$id successfully." else "Action failed."
        return "$status\n\n${readScreenText()}"
    }

    // Pure Raw Pinpoint Tap (0-1000 scale or absolute pixels)
    fun tapCoordinates(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        if (wm != null) wm.defaultDisplay.getRealMetrics(dm)
        else dm.setTo(resources.displayMetrics)

        val targetX = if (x > dm.widthPixels) Math.round((x / 1000.0f) * dm.widthPixels) else x
        val targetY = if (y > dm.heightPixels) Math.round((y / 1000.0f) * dm.heightPixels) else y

        val clampedX = Math.max(2, Math.min(targetX, dm.widthPixels - 2))
        val clampedY = Math.max(2, Math.min(targetY, dm.heightPixels - 2))

        val clickPath = Path().apply {
            moveTo(clampedX.toFloat(), clampedY.toFloat())
            lineTo(clampedX.toFloat(), clampedY.toFloat() + 1)
        }

        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 120)
        val builder = GestureDescription.Builder().addStroke(stroke)

        return dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                showToast("✓ Tapped ($clampedX, $clampedY)")
            }
        }, null)
    }

    // Samsung-Tuned Long-Press
    fun longPress(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        if (wm != null) wm.defaultDisplay.getRealMetrics(dm)
        else dm.setTo(resources.displayMetrics)

        val targetX = if (x > dm.widthPixels) Math.round((x / 1000.0f) * dm.widthPixels) else x
        val targetY = if (y > dm.heightPixels) Math.round((y / 1000.0f) * dm.heightPixels) else y

        val clampedX = Math.max(2, Math.min(targetX, dm.widthPixels - 2))
        val clampedY = Math.max(2, Math.min(targetY, dm.heightPixels - 2))

        val path = Path().apply {
            moveTo(clampedX.toFloat(), clampedY.toFloat())
            lineTo(clampedX.toFloat(), clampedY.toFloat() + 1)
        }

        val timeout = (ViewConfiguration.getLongPressTimeout() * 1.5f).toInt()
        val stroke = GestureDescription.StrokeDescription(path, 0, Math.max(750, timeout).toLong())
        val builder = GestureDescription.Builder().addStroke(stroke)

        return dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                showToast("✓ Long-pressed ($clampedX, $clampedY)")
            }
        }, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs).toLong())
        val builder = GestureDescription.Builder().addStroke(stroke)
        return dispatchGesture(builder.build(), null, null)
    }

    fun scroll(direction: String): Boolean {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        if (wm != null) wm.defaultDisplay.getRealMetrics(dm)
        else dm.setTo(resources.displayMetrics)

        val midX = dm.widthPixels / 2
        val h = dm.heightPixels
        return if ("up".equals(direction, ignoreCase = true)) {
            swipe(midX, (h * 0.3).toInt(), midX, (h * 0.8).toInt(), 300)
        } else {
            swipe(midX, (h * 0.8).toInt(), midX, (h * 0.3).toInt(), 300)
        }
    }

    // Rule #3: Candidate Extraction (#0, #1, #2...)
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return "No active window detected."

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        if (wm != null) wm.defaultDisplay.getRealMetrics(dm)
        else dm.setTo(resources.displayMetrics)

        activeCandidateBounds.clear()
        activeCandidateNodes.clear()

        val catalog = java.lang.StringBuilder()
        catalog.append("=== CANDIDATE INTERACTIVE ELEMENTS (USE tap_element_id) ===\n")
        val elements = ArrayList<String>()
        extractInteractiveElements(root, elements, dm, 0)
        if (elements.isEmpty()) {
            catalog.append("No labeled buttons detected.\n")
        } else {
            for (el in elements) catalog.append(el).append("\n")
        }

        catalog.append("\n=== SCREEN TEXT CONTENT ===\n")
        val textSb = java.lang.StringBuilder()
        extractTextRecursive(root, textSb, 0)
        catalog.append(textSb.toString().trim())

        return catalog.toString()
    }

    private fun extractInteractiveElements(node: AccessibilityNodeInfo?, list: MutableList<String>, dm: DisplayMetrics, depth: Int) {
        if (node == null || depth > 25 || list.size >= 30) return
        if (node.isClickable || node.isEditable || node.isFocusable || node.isLongClickable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 5 && r.height() > 5 && r.left < dm.widthPixels && r.top < dm.heightPixels) {
                val id = activeCandidateBounds.size
                activeCandidateBounds.add(Rect(r))
                activeCandidateNodes.add(node)

                val normX = Math.round((r.centerX() / dm.widthPixels.toFloat()) * 1000)
                val normY = Math.round((r.centerY() / dm.heightPixels.toFloat()) * 1000)
                val type = if (node.isEditable) "Input" else if (node.isLongClickable) "Item" else "Button"

                val text = node.text
                val desc = node.contentDescription
                var label = if (text != null) text.toString().trim() else if (desc != null) desc.toString().trim() else ""
                if (label.isEmpty()) {
                    label = if (node.viewIdResourceName != null) node.viewIdResourceName else "Button"
                }

                list.add("[#$id] [$type] \"$label\" at coordinates ($normX, $normY)")
            }
        }
        for (i in 0 until node.childCount) {
            extractInteractiveElements(node.getChild(i), list, dm, depth + 1)
        }
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo?, sb: java.lang.StringBuilder, depth: Int) {
        if (node == null || depth > 25) return
        val text = node.text
        if (!text.isNullOrEmpty()) {
            sb.append(text).append("\n")
        } else {
            val desc = node.contentDescription
            if (!desc.isNullOrEmpty()) {
                sb.append(desc).append("\n")
            }
        }
        for (i in 0 until node.childCount) {
            extractTextRecursive(node.getChild(i), sb, depth + 1)
        }
    }

    // Rule #5: Multi-Stage Text Replacement Engine (SET_TEXT -> SELECT_ALL/CUT/PASTE -> Samsung KB Dismiss)
    fun replaceText(newText: String): String {
        var root = rootInActiveWindow ?: return "No active window.\n\n${readScreenText()}"

        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) target = findFocusedNode(root, 0)
        if (target == null) target = findFirstEditable(root, 0)

        if (target == null) return "No editable field found.\n\n${readScreenText()}"

        // Strategy 1: Direct SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        var ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Strategy 2: Focus -> SELECT_ALL -> CUT -> PASTE
        if (!ok) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            SystemClock.sleep(50)
            val cur = target.text
            val len = cur?.length ?: 0
            if (len > 0) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                SystemClock.sleep(50)
                target.performAction(AccessibilityNodeInfo.ACTION_CUT)
                SystemClock.sleep(50)
            }
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("voice_replace", newText))
            } catch (ignored: Exception) {}
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        // Strategy 3: Samsung Keyboard Dismiss Fallback (Rule #5)
        if (!ok) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            SystemClock.sleep(120)
            root = rootInActiveWindow ?: return "Failed to dismiss keyboard.\n\n${readScreenText()}"
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root, 0)
            if (target != null) {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        if (ok) showToast("✓ Replaced text")
        SystemClock.sleep(200)
        val status = if (ok) "Replaced text successfully with: $newText" else "Failed to replace text."
        return "$status\n\n${readScreenText()}"
    }

    fun clearText(): String = replaceText("")

    fun typeText(text: String): Boolean {
        var root = rootInActiveWindow ?: return false

        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) focused = findFocusedNode(root, 0)

        if (focused != null) return pasteDirectly(focused, text)

        // Soft keyboard dismissal
        performGlobalAction(GLOBAL_ACTION_BACK)
        SystemClock.sleep(80)

        root = rootInActiveWindow ?: return false
        val editable = findFirstEditable(root, 0)
        if (editable != null) {
            val rect = Rect()
            editable.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                tapCoordinates(rect.centerX(), rect.centerY())
                SystemClock.sleep(200)
            }
            root = rootInActiveWindow ?: return false
            val nowFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (nowFocused != null) return pasteDirectly(nowFocused, text)
            return pasteDirectly(editable, text)
        }
        return false
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val res = findFocusedNode(node.getChild(i), depth + 1)
            if (res != null) return res
        }
        return null
    }

    private fun pasteDirectly(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("voice_input", text))
        } catch (ignored: Exception) {}

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        SystemClock.sleep(50)

        var ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!ok) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (ok) showToast("✓ Inserted text")
        return ok
    }

    fun tapElement(label: String): Boolean {
        val target = label.lowercase().trim()
        val root = rootInActiveWindow ?: return false

        val matches = ArrayList<AccessibilityNodeInfo>()
        collectMatches(root, target, matches, 0)
        if (matches.isEmpty()) return false

        var bestNode: AccessibilityNodeInfo? = null
        var minArea = Int.MAX_VALUE
        val r = Rect()
        for (n in matches) {
            n.getBoundsInScreen(r)
            val area = r.width() * r.height()
            if (area in 1 until minArea) {
                minArea = area
                bestNode = n
            }
        }

        if (bestNode != null) {
            bestNode.getBoundsInScreen(r)
            return tapCoordinates(r.centerX(), r.centerY())
        }
        return false
    }

    private fun collectMatches(node: AccessibilityNodeInfo?, target: String, list: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 25) return
        val text = node.text
        val desc = node.contentDescription
        val match = (text != null && text.toString().lowercase().contains(target)) ||
                    (desc != null && desc.toString().lowercase().contains(target))
        if (match) list.add(node)
        for (i in 0 until node.childCount) {
            collectMatches(node.getChild(i), target, list, depth + 1)
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }
}
