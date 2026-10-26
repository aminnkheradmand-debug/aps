package com.example.webapp

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** JS bridge — available to web content as window.AndroidBridge (all methods guarded). */
class AndroidBridge(private val activity: Activity, private val webView: WebView) {

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        val ms = durationMs.coerceIn(10L, 5000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(ms)
        }
    }

    @JavascriptInterface
    fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, title))
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("APS", text))
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String) {
        val channelId = "aps_default_channel"
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "App notifications", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(activity, channelId)
            .setSmallIcon(activity.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }

    @JavascriptInterface
    fun printPage() {
        activity.runOnUiThread {
            val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = webView.createPrintDocumentAdapter("Document")
            pm.print("WebView_Print", adapter, PrintAttributes.Builder().build())
        }
    }

    @JavascriptInterface
    fun saveBase64File(dataUrl: String, mimeType: String) {
        try {
            val comma = dataUrl.indexOf(',')
            val b64 = if (comma != -1) dataUrl.substring(comma + 1) else dataUrl
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            val outFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "download_" + System.currentTimeMillis() + "." + ext
            )
            FileOutputStream(outFile).use { it.write(bytes) }
            activity.runOnUiThread {
                Toast.makeText(activity, "Saved to Downloads: " + outFile.name, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                Toast.makeText(activity, "Save failed: " + (e.message ?: "?"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
        }.toString()
    }
}
