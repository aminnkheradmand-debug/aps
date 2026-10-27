package com.example.webapp

import android.content.Context
import android.hardware.input.InputManager
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import org.json.JSONObject

/**
 * PenHoverBridge — forwards S Pen / stylus hover to JavaScript.
 *
 * v4.7 final (per specialist report):
 *  - Dual entry points: setOnHoverListener (primary) + Activity.dispatchGenericMotionEvent (fallback)
 *  - Extracts: x, y, pressure, AXIS_DISTANCE, AXIS_TILT, buttonState, action
 *  - Sends via CustomEvent('native-hover') AND the existing __aspPenDispatch shim
 *  - Listener returns false so WebView's own CSS :hover still works
 *  - NO focusable flag (per report: not needed)
 */
class PenHoverBridge(private val webView: WebView) {

    var enabled = true
    var dispatchPointerEvents = true
    var dispatchCustomEvent = true
    var hoverHaptics = false
    var throttleMs = 16L
    var debugLog = false

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
                if (dev.supportsSource(InputDevice.SOURCE_STYLUS)) {
                    found = true
                    break
                }
            }
        } catch (_: Exception) {}
        stylusAvailable = if (found) 1 else 0
        if (debugLog) log("hasStylus=$found")
        return found
    }

    /** Primary: install onHoverListener on the WebView (report path A). */
    fun installOnWebView() {
        try {
            webView.setOnHoverListener { _, event ->
                handleHover(event, useViewCoords = true, source = "listener")
                false  // ← return false so WebView's own CSS :hover keeps working
            }
            if (debugLog) log("installOnWebView: listener attached")
        } catch (e: Exception) {
            if (debugLog) log("installOnWebView failed: ${e.message}")
        }
    }

    /** Fallback: called from Activity.dispatchGenericMotionEvent (report path B). */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        handleHover(event, useViewCoords = false, source = "dispatch")
        return false  // ← don't consume at Activity level either
    }

    /** Legacy compatibility. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handleHover(event, useViewCoords = false, source = "legacy")
        return false
    }

    private fun handleHover(event: MotionEvent, useViewCoords: Boolean, source: String) {
        if (!enabled) return
        if (!hasStylus()) return
        val toolType = event.getToolType(0)
        val isStylus = event.isFromSource(InputDevice.SOURCE_STYLUS) ||
                       toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                       toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus) return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> dispatch("move", event, useViewCoords, source)
            MotionEvent.ACTION_HOVER_ENTER -> dispatch("enter", event, useViewCoords, source)
            MotionEvent.ACTION_HOVER_EXIT -> dispatch("exit", event, useViewCoords, source)
        }
    }

    private fun dispatch(kind: String, e: MotionEvent, useViewCoords: Boolean, source: String) {
        try {
            if (kind == "move") {
                val now = e.eventTime
                if (throttleMs in 1 until 5000 && now - lastSent < throttleMs) return
                lastSent = now
            }
            val x: Float
            val y: Float
            if (useViewCoords) {
                x = e.x
                y = e.y
            } else {
                webView.getLocationOnScreen(loc)
                x = e.rawX - loc[0]
                y = e.rawY - loc[1]
            }
            if (x < 0 || y < 0) return

            val distance = try { e.getAxisValue(MotionEvent.AXIS_DISTANCE).toDouble() } catch (_: Exception) { 0.0 }
            val tilt = try { e.getAxisValue(MotionEvent.AXIS_TILT).toDouble() } catch (_: Exception) { 0.0 }
            val toolType = e.getToolType(0)
            val buttonState = e.buttonState

            val cfgPad = JSONObject().apply {
                put("pointerEvents", dispatchPointerEvents)
                put("customEvent", dispatchCustomEvent)
            }
            val payload = JSONObject().apply {
                put("type", kind)
                put("x", x.toDouble())
                put("y", y.toDouble())
                put("pressure", e.pressure.toDouble())
                put("distance", distance)
                put("tiltX", tilt)
                put("tiltY", 0.0)
                put("buttonState", buttonState)
                put("toolType", toolType)
                put("source", source)
                put("t", e.eventTime)
            }

            // Path 1: native-hover CustomEvent (report recommended — simpler, no dependency on shim)
            val nativeHoverJs =
                "window.__apsNativeHover&&window.__apsNativeHover(" + payload.toString() + ");"

            // Path 2: shim's __aspPenDispatch (which fires synthetic PointerEvents)
            val shimJs =
                "window.__ASPPEN_CFG=" + cfgPad + ";" +
                "window.__ASPEN_PHYSICAL_WIDTH=" + webView.width + ";" +
                "window.__aspPenDispatch&&window.__aspPenDispatch(" + payload + ");"

            val combined = nativeHoverJs + shimJs
            webView.post { webView.evaluateJavascript(combined, null) }

            if (hoverHaptics) webView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (debugLog) log("dispatch[$source] $kind x=$x y=$y p=${e.pressure} btn=$buttonState")
        } catch (ex: Exception) {
            if (debugLog) log("dispatch error: ${ex.message}")
        }
    }

    private fun log(msg: String) {
        try {
            webView.post { webView.evaluateJavascript("console.log('[PenHoverBridge] ' + " + JSONObject.quote(msg) + ");", null) }
        } catch (_: Exception) {}
    }
}
