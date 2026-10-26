package com.example.webapp

import android.content.Context
import android.hardware.input.InputManager
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject

class PenHoverBridge(private val webView: WebView) {

    var enabled = true
    var dispatchPointerEvents = true
    var dispatchCustomEvent = true
    var hoverHaptics = false
    var throttleMs = 16L

    private var stylusAvailable: Int = -1
    private var lastSent = 0L
    private val loc = IntArray(2)

    private fun hasStylus(): Boolean {
        if (stylusAvailable != -1) return stylusAvailable == 1
        var found = false
        try {
            val im = webView.context.getSystemService(Context.INPUT_SERVICE) as InputManager
            for (id in im.inputDeviceIds) {
                val dev = im.getInputDevice(id) ?: continue
                if (dev.supportsSource(InputDevice.SOURCE_STYLUS)) found = true
            }
        } catch (_: Exception) {}
        stylusAvailable = if (found) 1 else 0
        return found
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!enabled || !hasStylus()) return false
        if (!event.isFromSource(InputDevice.SOURCE_STYLUS) &&
            event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> dispatch("move", event)
            MotionEvent.ACTION_HOVER_ENTER -> dispatch("enter", event)
            MotionEvent.ACTION_HOVER_EXIT -> { dispatch("exit", event); true }
            else -> false
        }
    }

    private fun dispatch(kind: String, e: MotionEvent): Boolean {
        try {
            if (kind == "move") {
                val now = e.eventTime
                if (throttleMs in 1 until 5000 && now - lastSent < throttleMs) return true
                lastSent = now
            }
            webView.getLocationOnScreen(loc)
            val x = e.rawX - loc[0]
            val y = e.rawY - loc[1]
            if (x < 0 || y < 0) return true
            val cfgPad = JSONObject().apply {
                put("pointerEvents", dispatchPointerEvents)
                put("customEvent", dispatchCustomEvent)
            }
            val payload = JSONObject().apply {
                put("type", kind)
                put("x", x.toDouble())
                put("y", y.toDouble())
                put("pressure", e.pressure.toDouble())
                put("tiltX", e.getAxisValue(MotionEvent.AXIS_TILT).toDouble())
                put("tiltY", 0.0) // فقط AXIS_TILT موجود است
                put("t", e.eventTime)
            }
            val js = "window.__ASPPEN_CFG=" + cfgPad + ";" +
                "window.__aspPenDispatch&&window.__aspPenDispatch(" + payload + ");"
            webView.post { webView.evaluateJavascript(js, null) }
            if (hoverHaptics) webView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
        return true
    }
}
