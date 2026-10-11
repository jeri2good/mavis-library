package com.mavislibrary.app.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mavislibrary.app.BuildConfig
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.mavislibrary.app.MavisApp

/** Origin of the bundled web app. Already an allowed CORS origin on the Netlify functions. */
const val WEB_ORIGIN = "https://localhost"

/** Pure origin check; a hostname that merely starts with localhost is not trusted. */
fun isAppOrigin(url: String?): Boolean {
    if (url == null || !url.startsWith(WEB_ORIGIN)) return false
    return runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.rawAuthority in setOf("localhost", "localhost:443")
    }.getOrDefault(false)
}

/** Serves assets/web/ (copied from dist-android at build time) for https://localhost/. */
class BundledWebAssets(activity: ComponentActivity) : WebViewAssetLoader.PathHandler {
    private val assets = WebViewAssetLoader.AssetsPathHandler(activity)

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.substringBefore('?').substringBefore('#').trimStart('/')
        if (clean.split('/').any { it == ".." }) return null
        val file = if (clean.isEmpty() || clean.endsWith("/")) clean + "index.html" else clean
        return assets.handle("web/$file")
    }
}

/** Decides what the WebView may load in place; everything else goes to the system browser. */
object WebNavigationPolicy {
    private val externalSchemes = setOf("http", "https", "mailto", "tel")

    fun isAppUrl(uri: Uri): Boolean = uri.scheme == "https" && uri.host == "localhost" && (uri.port == -1 || uri.port == 443)

    fun canOpenExternally(uri: Uri): Boolean = uri.scheme?.lowercase() in externalSchemes

    /** document picker mime types from <input accept>, e.g. ".epub" or "application/epub+zip". */
    fun mimeTypesFor(accept: Array<String>?): Array<String> {
        val types = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { t ->
                when {
                    t == ".epub" -> "application/epub+zip"
                    t.startsWith(".") -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.drop(1)) ?: "*/*"
                    else -> t
                }
            }.distinct()
        // Many file managers label EPUBs application/octet-stream, so keep them selectable.
        return when {
            types.isEmpty() || "*/*" in types -> arrayOf("*/*")
            "application/epub+zip" in types -> (types + "application/octet-stream").distinct().toTypedArray()
            else -> types.toTypedArray()
        }
    }
}

/**
 * One WebView for every web-hosted tab (Shelf, Bible, Listen), so the web app keeps its state
 * while tabs switch. Owned by the activity; Compose screens attach and detach it.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebHost(private val activity: ComponentActivity) {
    /** True when the WebView has history inside the current tab, so Back should go there first. */
    var canGoBack by mutableStateOf(false)
        private set

    /** A microphone request waiting for the user to read the one-line reason. */
    var pendingMicRequest by mutableStateOf<PermissionRequest?>(null)
        private set

    private var baseHistoryIndex = 0
    private var loaded = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var micRequestAwaitingSystem: PermissionRequest? = null
    @Volatile private var hapticEnabled = true
    @Volatile private var volumeKeysEnabled = false
    @Volatile private var headsetKeysEnabled = true
    private val settingsJob: Job = activity.lifecycleScope.launch {
        (activity.application as MavisApp).container.settings.playback.collectLatest {
            hapticEnabled = it.hapticPageTurns
            volumeKeysEnabled = it.volumePageTurns
            headsetKeysEnabled = it.headsetPageTurns
        }
    }

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("localhost")
        .setHttpAllowed(false)
        .addPathHandler("/", BundledWebAssets(activity))
        .build()

    private val pickOne = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }
    private val pickMany = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }
    private val askMic = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRequestAwaitingSystem?.let { if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny() }
        micRequestAwaitingSystem = null
    }

    val webView: WebView by lazy { createWebView() }

    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        installServiceWorkerInterceptor()
        return WebView(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                setGeolocationEnabled(false)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, nativeMarkerScript(), setOf(WEB_ORIGIN))
            }
            addJavascriptInterface(NativeBridge(), "MavisAndroid")
            webViewClient = Client()
            webChromeClient = Chrome()
        }
    }

    private fun installServiceWorkerInterceptor() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        })
        ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.apply {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) allowFileAccess = false
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) allowContentAccess = false
        }
    }

    /** Shows a web route such as "#/bible" without adding a history entry, and makes it this tab's root. */
    @Volatile private var pendingHash: String? = null

    /** Queues a web route (for example a book page) to show the next time the Shelf web screen is entered. */
    fun openLater(hashRoute: String) { pendingHash = hashRoute }

    /** Owner access code from secure storage; pushed into the web app after each page load. Never logged. */
    @Volatile private var ownerCode: String? = null

    fun setOwnerCode(code: String?) {
        ownerCode = code
        pushOwnerCode()
    }

    private fun pushOwnerCode() {
        val code = ownerCode?.takeIf { it.isNotBlank() } ?: return
        activity.runOnUiThread {
            if (isAppOrigin(webView.url)) {
                webView.evaluateJavascript("window.MavisApp&&window.MavisApp.setAccessCode(${JSONObject.quote(code)});", null)
            }
        }
    }

    fun show(requestedRoute: String) {
        val hashRoute = pendingHash ?: requestedRoute
        pendingHash = null
        val view = webView
        if (!loaded) {
            loaded = true
            view.loadUrl("$WEB_ORIGIN/$hashRoute")
            baseHistoryIndex = 0
        } else {
            view.evaluateJavascript("location.replace(${JSONObject.quote(hashRoute)});", null)
            baseHistoryIndex = view.copyBackForwardList().currentIndex.coerceAtLeast(0)
        }
        updateBackState()
    }

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
        updateBackState()
    }

    fun onMicReasonAccepted() {
        val request = pendingMicRequest ?: return
        pendingMicRequest = null
        micRequestAwaitingSystem = request
        askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onMicReasonDeclined() {
        pendingMicRequest?.deny()
        pendingMicRequest = null
    }

    fun onResume() { if (loaded) webView.onResume() }
    fun onPause() { if (loaded) webView.onPause() }
    fun destroy() {
        settingsJob.cancel()
        if (!loaded) return
        fileCallback?.onReceiveValue(null)
        webView.destroy()
    }

    fun handlePageTurnKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || !webView.url.orEmpty().contains("#/read/")) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysEnabled) "next" else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysEnabled) "prev" else null
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (headsetKeysEnabled) "next" else null
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (headsetKeysEnabled) "prev" else null
            else -> null
        } ?: return false
        turnReaderPage(direction)
        return true
    }

    fun turnReaderPage(direction: String) {
        val method = if (direction == "next") "nextPage" else "prevPage"
        webView.evaluateJavascript("window.MavisReader?.$method?.()", null)
    }

    private inner class NativeBridge {
        @JavascriptInterface fun haptic(kind: String) {
            if (!hapticEnabled || kind != "tick") return
            activity.runOnUiThread {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    else @Suppress("DEPRECATION") activity.getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= 29) vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                else @Suppress("DEPRECATION") vibrator?.vibrate(8)
            }
        }
    }

    private fun updateBackState() {
        val index = webView.copyBackForwardList().currentIndex
        canGoBack = webView.canGoBack() && index > baseHistoryIndex
    }

    private fun openExternally(uri: Uri) {
        if (!WebNavigationPolicy.canOpenExternally(uri)) return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app on this device can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (WebNavigationPolicy.isAppUrl(request.url)) return false
            if (request.isForMainFrame) openExternally(request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // Fallback for old WebView builds without document-start scripts (best effort, may run after page scripts).
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(nativeMarkerScript(), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // WebView delivers onPageFinished only for the main frame.
            if (isAppOrigin(url) && isAppOrigin(view.url)) pushOwnerCode()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBackState()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val types = WebNavigationPolicy.mimeTypesFor(params.acceptTypes)
            return try {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pickMany.launch(types) else pickOne.launch(types)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (request.origin.toString().trimEnd('/') != WEB_ORIGIN || !wantsMic) {
                request.deny()
                return
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingMicRequest?.deny()
                pendingMicRequest = request
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingMicRequest == request) pendingMicRequest = null
        }
    }

    companion object {
        fun nativeMarkerScript(): String {
            val info = JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("build", BuildConfig.GIT_SHA)
                .put("platform", "android")
            return "window.MavisNative = Object.freeze({...$info,haptic:(kind)=>window.MavisAndroid?.haptic(String(kind))});"
        }
    }
}
