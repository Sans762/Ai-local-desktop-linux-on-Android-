package com.localai.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only after the user explicitly enables the Android Accessibility service. */
class AutomationAccessibilityService : AccessibilityService() {
    companion object { @Volatile var current: AutomationAccessibilityService? = null }
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { super.onServiceConnected(); current = this }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun observe(): JSONObject {
        val out = JSONArray()
        val root = rootInActiveWindow ?: return JSONObject().put("available", false).put("nodes", out)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var count = 0
        while (queue.isNotEmpty() && count < 100) {
            val (node, depth) = queue.removeFirst()
            count++
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val label = if (node.isPassword) "[password-redacted]" else
                (node.text?.toString() ?: node.contentDescription?.toString() ?: "").take(100)
            if (label.isNotEmpty() || node.isClickable || node.isEditable) {
                out.put(JSONObject().put("text", label).put("clickable", node.isClickable)
                    .put("editable", node.isEditable && !node.isPassword)
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
            }
            if (depth < 5) for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return JSONObject().put("available", true).put("package", root.packageName?.toString() ?: "")
            .put("nodes", out)
    }

    fun tap(x: Int, y: Int): Boolean {
        val latch = CountDownLatch(1)
        var done = false
        main.post {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 90)).build()
            val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, main)
            if (!submitted) latch.countDown()
        }
        return latch.await(4, TimeUnit.SECONDS) && done
    }

    fun goBack(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun goHome(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_HOME) }

    /** This is intentionally restricted to an already focused, non-password editable field. */
    fun typeIntoFocused(text: String): Boolean {
        require(text.length <= 800) { "Text too long" }
        return onMain {
            val root = rootInActiveWindow ?: return@onMain false
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@onMain false
            if (!field.isEditable || field.isPassword) return@onMain false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun onMain(block: () -> Boolean): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        main.post {
            try { result = block() } finally { latch.countDown() }
        }
        return latch.await(4, TimeUnit.SECONDS) && result
    }
}