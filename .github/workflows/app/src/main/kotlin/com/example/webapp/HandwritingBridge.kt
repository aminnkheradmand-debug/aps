package com.example.webapp

import android.app.AlertDialog
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DownloadConditions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RemoteModelManager

/**
 * HandwritingBridge — strokes-to-text for web content via window.ASPHandwriting.
 * Engine "auto/mlkit": ML Kit Digital Ink Recognition (on-device; the model downloads ONCE and works offline afterwards), with the system IME (Samsung Keyboard handwriting) as fallback.
 * Results flow back through window.__aspHandwritingResolve(reqId, ok, text).
 */
class HandwritingBridge(private val webView: WebView) {

    private val model: DigitalInkRecognitionModel by lazy {
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.LATIN).build()
    }
    private val recognizer: DigitalInkRecognizer by lazy {
        DigitalInkRecognizer.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }
    private var mlKitReady = false

    init {
        RemoteModelManager.getInstance().isModelDownloaded(model).addOnSuccessListener { downloaded ->
            mlKitReady = downloaded
            reportEngine()
        }
    }

    private fun reportEngine() {
        val info = JSONObject().put("engine", if (mlKitReady) "mlkit" else "auto")
            .put("modelReady", mlKitReady)
        webView.post {
            webView.evaluateJavascript("window.__aspHandwritingEngine&&window.__aspHandwritingEngine(" + info + ");", null)
        }
    }

    private fun ensureModel(then: (ok: Boolean) -> Unit) {
        RemoteModelManager.getInstance().isModelDownloaded(model).addOnSuccessListener { downloaded ->
            if (downloaded) { mlKitReady = true; then(true); return@addOnSuccessListener }
            RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { mlKitReady = true; reportEngine(); then(true) }
                .addOnFailureListener { then(false) }
        }
    }

    private fun inkFromJson(strokesJson: String): Ink? {
        return try {
            val inkBuilder = Ink.builder()
            val strokes = JSONArray(strokesJson)
            for (s in 0 until strokes.length()) {
                val strokeObj = strokes.optJSONObject(s) ?: continue
                val points = strokeObj.optJSONArray("points") ?: continue
                val sb = Ink.Stroke.builder()
                for (p in 0 until points.length()) {
                    val pt = points.optJSONObject(p) ?: continue
                    sb.addPoint(Ink.Point.create(pt.optDouble("x"), pt.optDouble("y"), pt.optLong("t")))
                }
                inkBuilder.addStroke(sb.build())
            }
            inkBuilder.build()
        } catch (_: Exception) { null }
    }

    @JavascriptInterface
    fun recognizeRequest(reqId: String, strokesJson: String) {
        /* strokesJson carries the full Ink document: {"strokes":[{"points":[{x,y,t}]}]} */
        val ink = inkFromJson(strokesJson) ?: run { finish(reqId, false, "BAD_INK_JSON"); return }
        ensureModel { ok ->
            if (!ok) { imeFallback(reqId); return@ensureModel }
            recognizer.recognize(ink)
                .addOnSuccessListener { result ->
                    val text = result.candidates.firstOrNull()?.text ?: ""
                    if (text.isEmpty()) imeFallback(reqId)
                    else finish(reqId, true, text)
                }
                .addOnFailureListener { e -> imeFallback(reqId) }
        }
    }

    @JavascriptInterface
    fun openSystemInputRequest(reqId: String, unused: String) { imeFallback(reqId) }

    private fun imeFallback(reqId: String) {
        webView.post {
            val ctx = webView.context
            val input = EditText(ctx).apply {
                hint = "Write here (Samsung Keyboard handwriting)"
                minLines = 1
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            val pad = (ctx.resources.displayMetrics.density * 16).toInt()
            val wrap = android.widget.LinearLayout(ctx).apply { setPadding(pad, pad, pad, pad) }
            wrap.addView(input, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            AlertDialog.Builder(ctx)
                .setTitle("Handwriting input")
                .setView(wrap)
                .setPositiveButton("OK") { _, _ -> finish(reqId, true, input.text.toString()) }
                .setNegativeButton("Cancel") { _, _ -> finish(reqId, false, "CANCELLED") }
                .setOnCancelListener { finish(reqId, false, "CANCELLED") }
                .show()
            input.post {
                input.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun finish(reqId: String, ok: Boolean, value: String) {
        val esc = JSONObject().put("v", value).toString()
        webView.post {
            webView.evaluateJavascript(
                "window.__aspHandwritingResolve&&window.__aspHandwritingResolve(" +
                JSON.quote(reqId) + "," + (if (ok) "true" else "false") + "," + esc.substring(esc.indexOf(':') + 1, esc.length - 1) + ");",
                null
            )
        }
    }
}
