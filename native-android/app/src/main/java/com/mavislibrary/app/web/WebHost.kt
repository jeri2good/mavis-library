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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.kids.kidsPrefs

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

    var onPodcast: ((com.mavislibrary.app.data.podcast.PodcastDraft) -> Unit)? = null
    var onArticle: ((com.mavislibrary.app.data.podcast.PodcastDraft) -> Unit)? = null

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
                WebViewCompat.addDocumentStartJavaScript(this, nativeMarkerScript() + accountTransportScript(BuildConfig.API_BASE), setOf(WEB_ORIGIN))
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "MavisPodcast", setOf(WEB_ORIGIN)) { view, message, origin, mainFrame, _ ->
                    if (mainFrame && isAppOrigin(origin.toString()) && isAppOrigin(view.url)) activity.lifecycleScope.launch {
                        val kids = activity.kidsPrefs().state.first()
                        if (!com.mavislibrary.app.data.podcast.loungeRouteAllowed(kids.enabled)) return@launch
                        runCatching { PodcastWebShortcut.parse(message.data.orEmpty()) }.onSuccess {
                            if (it.openArticle) onArticle?.invoke(it.draft) else onPodcast?.invoke(it.draft)
                        }.onFailure {
                            Toast.makeText(activity, it.message ?: "Could not prepare that source.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            addJavascriptInterface(NativeBridge(), "MavisAndroid")
            webViewClient = Client()
            webChromeClient = Chrome()
        }
    }

    private fun accountTransportScript(base: String): String =
        "window.MavisNativeApiBase=${JSONObject.quote(base)};" + activity.assets.open("native-account-transport.js").bufferedReader().use { it.readText() }

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

    @Volatile private var accountToken: String? = null

    fun setAccountToken(token: String?) {
        accountToken = token
        activity.runOnUiThread {
            if (loaded && isAppOrigin(webView.url)) {
                webView.evaluateJavascript("window.MavisNativeAccount?.setToken(${JSONObject.quote(token.orEmpty())});", null)
            }
        }
    }

    private fun pushOwnerCode() {
        val code = ownerCode.orEmpty()
        activity.runOnUiThread {
            if (loaded && isAppOrigin(webView.url)) {
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

    /** Boots the web app off-screen (if no web tab has yet) so native screens can query its local store. */
    fun warm() {
        if (loaded) return
        loaded = true
        webView.loadUrl("$WEB_ORIGIN/#/")
        baseHistoryIndex = 0
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

        @JavascriptInterface fun readingProgress(payload: String) {
            if (payload.length > 16_000) return
            activity.runOnUiThread {
                if (!isAppOrigin(webView.url)) return@runOnUiThread
                activity.lifecycleScope.launch {
                    if (activity.kidsPrefs().state.first().enabled) return@launch
                    runCatching {
                        val json = JSONObject(payload)
                        val key = json.optString("key").trim()
                        if (key.isEmpty() || key == "bible" || key.length > 500 || key.any { it.code < 32 }) return@launch
                        val rawSource = json.optString("source").trim().lowercase()
                        val sourceId = json.optString("sourceId").trim()
                        if (com.mavislibrary.app.data.kids.kidsBook(rawSource, sourceId)) return@launch
                        val c = (activity.application as MavisApp).container
                        val now = System.currentTimeMillis()
                        if (c.database.books().get(key) == null) {
                            val sourceName = if (rawSource == "openlibrary" || rawSource == "open_library") "OPEN_LIBRARY" else "GUTENBERG"
                            val authors = json.optJSONArray("authors")?.let { arr ->
                                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.joinToString("\u001F")
                            }.orEmpty()
                            val cover = json.optString("coverUrl").takeIf { it.startsWith("https://") }
                            c.database.books().upsert(com.mavislibrary.app.data.db.BookEntity(
                                key = key, source = sourceName, sourceId = sourceId.ifBlank { key.substringAfter(':') },
                                title = json.optString("title").ifBlank { "Untitled" }.take(300), authors = authors,
                                coverUrl = cover, formats = "EPUB|true", year = null, languages = "en",
                                epubUrl = null, sourceUrl = null, updatedAt = now,
                            ))
                        }
                        val cfi = json.optString("cfi").takeIf { it.startsWith("epubcfi(") && it.length <= 2000 }
                        val prev = c.database.readingProgress().get(key)
                        val rawPercent = json.optDouble("percent", Double.NaN)
                        val fraction = if (!rawPercent.isNaN()) rawPercent.toFloat().coerceIn(0f, 1f) else (prev?.fraction ?: 0f)
                        c.database.readingProgress().put(com.mavislibrary.app.data.db.ReadingProgressEntity(
                            bookKey = key, locator = cfi ?: prev?.locator, fraction = fraction, updatedAt = now,
                        ))
                        c.settings.setLastOpenedBook(key)
                        com.mavislibrary.app.work.WidgetUpdater.resetBookSelection(activity)
                        com.mavislibrary.app.work.WidgetUpdater.refresh(activity)
                    }
                }
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
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(nativeMarkerScript() + accountTransportScript(BuildConfig.API_BASE), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // WebView delivers onPageFinished only for the main frame.
            if (isAppOrigin(url) && isAppOrigin(view.url)) {
                setAccountToken(accountToken)
                pushOwnerCode()
            }
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
            return "window.MavisNative = Object.freeze({...$info,haptic:(kind)=>window.MavisAndroid?.haptic(String(kind)),readingProgress:(payload)=>window.MavisAndroid?.readingProgress?.(String(payload))});"
        }
    }
}
