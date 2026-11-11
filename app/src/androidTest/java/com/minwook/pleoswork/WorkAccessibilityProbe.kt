package com.minwook.pleoswork

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Test APK only. Observes real accessibility events without suppressing TalkBack. */
class WorkAccessibilityProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    override fun onStart() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val snapshots = JSONArray()
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun nodeLabel(n: AccessibilityNodeInfo): String {
            val result = mutableListOf<String>()
            n.contentDescription?.let { result.add(it.toString()) }; n.text?.let { result.add(it.toString()) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { result.add(nodeLabel(it)); it.recycle() }
            return result.filter { it.isNotBlank() }.joinToString(" | ")
        }
        automation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == targetContext.packageName && event.eventType in setOf(
                AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_ANNOUNCEMENT, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                val source = event.source
                val label = source?.let(::nodeLabel) ?: (listOfNotNull(event.contentDescription?.toString()) + event.text.map { it.toString() }).joinToString(" | ")
                source?.recycle()
                events.add("${AccessibilityEvent.eventTypeToString(event.eventType)}: $label")
            }
        }
        fun focus() {
            val root = automation.rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val accessible = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            snapshots.put(JSONObject().put("input", focused?.let(::nodeLabel) ?: "")
                .put("talkback", accessible?.let(::nodeLabel) ?: ""))
            focused?.recycle(); accessible?.recycle(); root?.recycle()
        }
        fun key(code: Int, shift: Boolean = false) {
            val at = SystemClock.uptimeMillis()
            val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) automation.injectInputEvent(
                KeyEvent(at, at, action, code, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
        }
        fun talkBackKey(code: Int) {
            val device = InputDevice.getDeviceIds().firstOrNull { InputDevice.getDevice(it)?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } ?: -1
            val at = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            fun send(action: Int, key: Int, flags: Int) = automation.injectInputEvent(
                KeyEvent(at,SystemClock.uptimeMillis(),action,key,0,flags,device,0,0,InputDevice.SOURCE_KEYBOARD),true)
            send(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ALT_LEFT,meta)
            send(KeyEvent.ACTION_DOWN,code,meta); send(KeyEvent.ACTION_UP,code,meta)
            send(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ALT_LEFT,0)
        }
        try {
            startActivitySync(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
            SystemClock.sleep(1200)
            fun clickLabel(node: AccessibilityNodeInfo, label: String): Boolean {
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                    var target: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                    while (target != null && !target.isClickable) { val parent = target.parent; target.recycle(); target = parent }
                    val result = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    target?.recycle(); if (result) return true
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                    val result = clickLabel(child, label); child.recycle(); if (result) return true
                }
                return false
            }
            args.getString("page")?.let { label ->
                val root = automation.rootInActiveWindow
                check(root != null && clickLabel(root, label)) { "Visible panel action not found: $label" }
                root.recycle(); SystemClock.sleep(600)
            }
            val mode = args.getString("mode", "observe")
            val steps = args.getString("steps", "10").toInt().coerceIn(1, 80)
            when (mode) {
                "sequence" -> args.getString("sequence", "TAB").split(',').take(100).forEach { command ->
                    when(command.trim()) {
                        "TAB" -> key(KeyEvent.KEYCODE_TAB)
                        "SHIFT_TAB" -> key(KeyEvent.KEYCODE_TAB,true)
                        "ENTER" -> key(KeyEvent.KEYCODE_ENTER)
                        "BACK" -> key(KeyEvent.KEYCODE_BACK)
                        "ALT_RIGHT" -> talkBackKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        "ALT_LEFT" -> talkBackKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        "ALT_ENTER" -> talkBackKey(KeyEvent.KEYCODE_ENTER)
                        else -> error("Unknown key command")
                    }
                    SystemClock.sleep(350); focus()
                }
                "keyboard" -> repeat(steps) { key(KeyEvent.KEYCODE_TAB, args.getString("reverse") == "true"); SystemClock.sleep(280); focus() }
                "talkback" -> repeat(steps) { talkBackKey(if(args.getString("reverse")=="true") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(350); focus() }
                "accessibility" -> {
                    val targets = mutableListOf<AccessibilityNodeInfo>()
                    fun collect(node: AccessibilityNodeInfo) {
                        if (node.isVisibleToUser && (node.isScreenReaderFocusable || node.isClickable ||
                            node.childCount == 0 && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())))
                            targets.add(AccessibilityNodeInfo.obtain(node))
                        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it); it.recycle() }
                    }
                    automation.rootInActiveWindow?.let { collect(it); it.recycle() }
                    targets.take(steps).forEach { node ->
                        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        events.add("ACCESSIBILITY_ACTION: $accepted: ${nodeLabel(node)}")
                        SystemClock.sleep(200); focus()
                    }
                    targets.forEach { it.recycle() }
                }
                "enter" -> { key(KeyEvent.KEYCODE_ENTER); SystemClock.sleep(900); focus() }
                else -> repeat(steps) { focus(); SystemClock.sleep(1000) }
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", JSONObject().put("focus", snapshots).put("events", JSONArray(synchronized(events) { events.toList() })).toString())
            })
        } catch (e: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Probe failed: ${e.javaClass.simpleName}: ${e.message}") })
        } finally { automation.setOnAccessibilityEventListener(null) }
    }
}
