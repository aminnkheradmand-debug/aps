package com.example.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.MotionEvent
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private var splashOverlay: FrameLayout? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var cfg: JSONObject
    private lateinit var penHover: PenHoverBridge

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val uris = mutableListOf<Uri>()
            if (data.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                uris.add(data.data!!)
            }
            cb.onReceiveValue(uris.toTypedArray())
        } else {
            cb.onReceiveValue(null)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = loadConfigJson()
        val disp = cfg.optJSONObject("display") ?: JSONObject()
        val splash = cfg.optJSONObject("splash") ?: JSONObject()
        val wvCfg = cfg.optJSONObject("webview") ?: JSONObject()

        requestedOrientation = when (disp.optString("orientation", "portrait")) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (disp.optBoolean("keepScreenOn", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && disp.optBoolean("showCameraCutout", true)) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val primaryColor = parseHexColor(disp.optString("primaryColor", "#0F766E"), Color.parseColor("#0F766E"))
        val bgColor = parseHexColor(disp.optString("pageLoadingBgColor", "#0C1017"), Color.parseColor("#0C1017"))
        window.statusBarColor = primaryColor
        window.decorView.setBackgroundColor(bgColor)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (!disp.optBoolean("showStatusBar", true)) {
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        if (disp.optBoolean("showActionBar", false)) {
            val actionBar = TextView(this).apply {
                text = cfg.optString("appName", title.toString())
                setTextColor(Color.WHITE)
                setBackgroundColor(primaryColor)
                textSize = 18f
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            mainColumn.addView(actionBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = if (wvCfg.optBoolean("loadingIndicator", true)) View.VISIBLE else View.GONE
        }
        mainColumn.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ))

        swipeRefresh = SwipeRefreshLayout(this).apply {
            isEnabled = wvCfg.optBoolean("pullToRefresh", true)
            setColorSchemeColors(primaryColor)
        }
        webView = WebView(this).apply { setBackgroundColor(bgColor) }
        swipeRefresh.addView(webView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        swipeRefresh.setOnRefreshListener { webView.reload() }
        mainColumn.addView(swipeRefresh, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(mainColumn)

        if (disp.optBoolean("showWatermark", false)) {
            val wmText = disp.optString("watermarkText", "")
            if (wmText.isNotEmpty()) {
                val wm = TextView(this).apply {
                    text = wmText
                    setTextColor(0x66FFFFFF.toInt())
                    textSize = 11f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                root.addView(wm, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ))
            }
        }

        if (splash.optBoolean("enabled", true)) {
            val splashView = buildCustomSplashOverlay(splash)
            splashOverlay = splashView
            root.addView(splashView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            val duration = splash.optLong("displayDurationMs", 1800L).coerceAtLeast(300L)
            val fadeMs = splash.optLong("fadeDurationMs", 350L).coerceAtLeast(100L)
            root.postDelayed({ hideSplashOverlay(fadeMs) }, duration)
        }

        setContentView(root)
        requestConfiguredPermissions()
        configureWebView(wvCfg, disp)
        penHover = PenHoverBridge(webView).apply {
            val spenCfg = cfg.optJSONObject("spen") ?: JSONObject()
            enabled = spenCfg.optBoolean("enabled", true)
            dispatchPointerEvents = spenCfg.optBoolean("dispatchPointerEvents", true)
            dispatchCustomEvent = spenCfg.optBoolean("dispatchCustomEvent", true)
            hoverHaptics = spenCfg.optBoolean("hoverHaptics", false)
            throttleMs = spenCfg.optLong("hoverThrottleMs", 16L).coerceIn(0L, 500L)
        }
        setupBackNavigation(wvCfg)
        loadInitialContent(wvCfg)
    }

    private fun buildCustomSplashOverlay(splash: JSONObject): FrameLayout {
        val sBg = parseHexColor(splash.optString("backgroundColor", "#0C1017"), Color.parseColor("#0C1017"))
        val lColor = parseHexColor(splash.optString("loaderColor", "#14B8A6"), Color.parseColor("#14B8A6"))
        val iconSizePx = dp(splash.optInt("iconSizeDp", 96))
        val iconCornersPx = dp(splash.optInt("iconCornersDp", 22)).toFloat()
        val loaderSizePx = dp(splash.optInt("loaderSizeDp", 36))
        val spacingPx = dp(splash.optInt("spacingDp", 24))
        val loaderStyle = splash.optString("loaderStyle", "circle")

        val container = FrameLayout(this).apply {
            setBackgroundColor(sBg)
            isClickable = true
            isFocusable = true
        }
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val iconView = ImageView(this).apply {
            setImageResource(applicationInfo.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = iconCornersPx }
            clipToOutline = true
        }
        centerCol.addView(iconView, LinearLayout.LayoutParams(iconSizePx, iconSizePx))

        if (loaderStyle != "none") {
            if (loaderStyle == "dots") {
                val dots = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (i in 0 until 3) {
                    val dot = TextView(this).apply {
                        text = "\u25CF"
                        setTextColor(lColor)
                        textSize = splash.optInt("loaderSizeDp", 36) / 3f
                        setPadding(dp(4), 0, dp(4), 0)
                    }
                    dot.alpha = 0.25f + 0.25f * i
                    dots.addView(dot, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
                centerCol.addView(dots, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = spacingPx })
            } else {
                val loader = ProgressBar(
                    this,
                    null,
                    if (loaderStyle == "bar") android.R.attr.progressBarStyleHorizontal
                    else android.R.attr.progressBarStyleLarge
                ).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(lColor)
                }
                val lp = LinearLayout.LayoutParams(
                    if (loaderStyle == "bar") iconSizePx * 2 else loaderSizePx,
                    if (loaderStyle == "bar") dp(splash.optInt("loaderThicknessDp", 4).coerceAtLeast(2)) else loaderSizePx
                ).apply { topMargin = spacingPx }
                centerCol.addView(loader, lp)
            }
        }

        container.addView(centerCol, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return container
    }

    private fun hideSplashOverlay(fadeMs: Long) {
        val view = splashOverlay ?: return
        splashOverlay = null
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wvCfg: JSONObject, disp: JSONObject) {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = wvCfg.optBoolean("javaScriptEnabled", true)
            domStorageEnabled = wvCfg.optBoolean("domStorageEnabled", true)
            databaseEnabled = true
            allowFileAccess = wvCfg.optBoolean("allowFileAccess", true)
            allowContentAccess = wvCfg.optBoolean("allowContentAccess", true)
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = wvCfg.optBoolean("universalFileAccess", true)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = wvCfg.optBoolean("fileAccessFromFileUrls", true)
            builtInZoomControls = wvCfg.optBoolean("builtInZoomControls", false)
            displayZoomControls = false
            useWideViewPort = wvCfg.optBoolean("useWideViewPort", true)
            loadWithOverviewMode = wvCfg.optBoolean("loadWithOverviewMode", true)
            setGeolocationEnabled(wvCfg.optBoolean("geolocationEnabled", false))
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            if (disp.optBoolean("desktopMode", false)) {
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            cacheMode = when (wvCfg.optString("cacheMode", "LOAD_DEFAULT")) {
                "LOAD_CACHE_ELSE_NETWORK" -> WebSettings.LOAD_CACHE_ELSE_NETWORK
                "LOAD_NO_CACHE" -> WebSettings.LOAD_NO_CACHE
                "LOAD_CACHE_ONLY" -> WebSettings.LOAD_CACHE_ONLY
                else -> WebSettings.LOAD_DEFAULT
            }

            mixedContentMode = when (wvCfg.optString("mixedContentMode", "MIXED_CONTENT_COMPATIBILITY_MODE")) {
                "MIXED_CONTENT_ALWAYS_ALLOW" -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                "MIXED_CONTENT_NEVER_ALLOW" -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
                else -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }

        webView.addJavascriptInterface(AndroidBridge(this, webView), "AndroidBridge")
        webView.addJavascriptInterface(HandwritingBridge(webView), "ASPHandwritingNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val spenCfg = cfg.optJSONObject("spen") ?: JSONObject()
                if (spenCfg.optBoolean("enabled", true)) {
                    val shim = readAssetText("www/asp-pen.js") ?: ""
                    if (shim.isNotEmpty()) view.evaluateJavascript(shim, null)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")
                    || url.startsWith("https://appassets.androidplatform.net")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
                view.evaluateJavascript(
                    "(function(){if(!window.__apsHooked){window.__apsHooked=true;" +
                    "window.print=function(){if(window.AndroidBridge)window.AndroidBridge.printPage();};" +
                    "if(window.AndroidBridge&&!window.__apsOfflineHooked){window.__apsOfflineHooked=true;}})();",
                    null
                )
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && wvCfg.optBoolean("noInternetDialog", true)) {
                    runOnUiThread { showOfflineDialog(wvCfg) }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (wvCfg.optBoolean("loadingIndicator", true)) {
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    progressBar.progress = newProgress
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val perms = cfg.optJSONObject("permissions") ?: JSONObject()
                val allowed = request.resources.filter { res ->
                    (res != PermissionRequest.RESOURCE_VIDEO_CAPTURE && res != PermissionRequest.RESOURCE_AUDIO_CAPTURE) ||
                        perms.optBoolean("camera", false)
                }.toTypedArray()
                runOnUiThread { request.grant(allowed) }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val perms = cfg.optJSONObject("permissions") ?: JSONObject()
                callback.invoke(origin, perms.optBoolean("location", false), false)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                injectBlobDownloadScript(url, mimeType)
            } else {
                try {
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(req)
                    Toast.makeText(this, "Download started…", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
    }

    private fun showOfflineDialog(wvCfg: JSONObject) {
        if (splashOverlay != null) return
        AlertDialog.Builder(this)
            .setTitle("No internet connection")
            .setMessage("Please check your connection and try again.")
            .setCancelable(false)
            .setPositiveButton("Retry") { _, _ -> loadInitialContent(wvCfg) }
            .setNeutralButton("Offline page") { _, _ ->
                webView.loadUrl("https://appassets.androidplatform.net/assets/www/offline.html")
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun injectBlobDownloadScript(blobUrl: String, mimeType: String?) {
        val js = "(function() { fetch('" + blobUrl + "').then(function(r){return r.blob();}).then(function(b) {" +
            "var reader = new FileReader(); reader.onloadend = function() {" +
            "window.AndroidBridge.saveBase64File(reader.result, '" + (mimeType ?: "application/octet-stream") + "');" +
            "}; reader.readAsDataURL(b); }); })();"
        webView.evaluateJavascript(js, null)
    }

    private fun setupBackNavigation(wvCfg: JSONObject) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wvCfg.optBoolean("backButtonNavigation", true) && webView.canGoBack()) {
                    webView.goBack()
                } else if (wvCfg.optBoolean("exitConfirmDialog", true)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(cfg.optString("appName", "Exit"))
                        .setMessage("Do you want to exit the app?")
                        .setPositiveButton("Yes") { _, _ -> finish() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private fun loadInitialContent(wvCfg: JSONObject) {
        val entryUrl = cfg.optString("entryUrl", "https://appassets.androidplatform.net/assets/www/index.html")
        val isRemote = entryUrl.startsWith("http") && !entryUrl.contains("appassets.androidplatform.net")
        if (isRemote && !isNetworkAvailable() && wvCfg.optBoolean("noInternetDialog", true)) {
            AlertDialog.Builder(this)
                .setTitle("No internet connection")
                .setMessage("Please check your connection and try again.")
                .setCancelable(false)
                .setPositiveButton("Retry") { _, _ -> loadInitialContent(wvCfg) }
                .setNegativeButton("Exit") { _, _ -> finish() }
                .show()
            return
        }
        webView.loadUrl(entryUrl)
    }

    private fun requestConfiguredPermissions() {
        val permsObj = cfg.optJSONObject("permissions") ?: return
        val needed = mutableListOf<String>()
        if (permsObj.optBoolean("camera", false)) needed.add(Manifest.permission.CAMERA)
        if (permsObj.optBoolean("location", false)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (permsObj.optBoolean("notifications", false)) needed.add(Manifest.permission.POST_NOTIFICATIONS)
            if (permsObj.optBoolean("media", false)) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
                needed.add(Manifest.permission.READ_MEDIA_VIDEO)
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        if (permsObj.optBoolean("activityRecognition", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (permsObj.optBoolean("phoneState", false)) needed.add(Manifest.permission.READ_PHONE_STATE)
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) permissionLauncher.launch(toAsk.toTypedArray())
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadConfigJson(): JSONObject {
        return try {
            val text = assets.open("www/config.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val spenCfg = cfg.optJSONObject("spen") ?: JSONObject()
        if (spenCfg.optBoolean("enabled", true) && this::penHover.isInitialized) {
            if (penHover.onGenericMotionEvent(event)) return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun readAssetText(path: String): String? {
        return try {
            assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }
    }

    private fun parseHexColor(hex: String, fallback: Int): Int {
        return try { Color.parseColor(hex) } catch (_: Exception) { fallback }
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
