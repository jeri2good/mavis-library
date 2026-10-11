package com.mavislibrary.app.ui.kids

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.view.ActionMode
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mavislibrary.app.web.BundledWebAssets
import org.json.JSONObject

private class KidsLockedWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
}

/** Uses the same bundled book/download/reader and Bible routes as Shelf, in an isolated restricted view.
 * No native bridge, owner-code injection, permission prompts or external navigation are installed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KidsWebReader(route: String, modifier: Modifier, onReading: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reading by rememberUpdatedState(onReading)
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        KidsText("This tablet needs an Android System WebView update to open stories.")
        return
    }
    val view = remember(route) {
        val assets = WebViewAssetLoader.Builder().setDomain("localhost")
            .addPathHandler("/", BundledWebAssets(context as ComponentActivity)).build()
        KidsLockedWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            setOnLongClickListener { true }
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setDownloadListener { _, _, _, _, _ -> }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                override fun onShowFileChooser(view: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = assets.shouldInterceptRequest(r.url)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                    !kidsNavigationAllowed(r.url.toString(), route, r.isForMainFrame)
                override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) {
                    val allowed = kidsWebRouteAllowed(url.orEmpty(), route)
                    reading(allowed && url.orEmpty().contains("#/read/"))
                    if (!allowed) { v.stopLoading(); v.loadUrl("https://localhost/$route") }
                }
            }
            WebViewCompat.addDocumentStartJavaScript(this, kidsReaderScript(route), setOf("https://localhost"))
            loadUrl("https://localhost/$route")
        }
    }
    DisposableEffect(view) { onDispose { reading(false); view.stopLoading(); view.destroy() } }
    AndroidView(factory = { view }, modifier = modifier)
}

/** Installed before the web router. Route checks also run natively for full navigations. */
internal fun kidsReaderScript(route: String): String {
    val selected = JSONObject.quote(java.net.URLDecoder.decode(route, "UTF-8"))
    return """
        (() => {
          window.MavisNative = Object.freeze({platform:'android'});
          const fetchOriginal = window.fetch;
          window.fetch = function(input, init) {
            const request = new Request(input, init);
            const headers = new Headers(request.headers);
            headers.delete('x-mavis-access');
            headers.set('x-mavis-kids', '1');
            return fetchOriginal.call(this, new Request(request, {headers}));
          };
          const setHeader = XMLHttpRequest.prototype.setRequestHeader;
          const send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
            if (String(name).toLowerCase() === 'x-mavis-access' || String(name).toLowerCase() === 'x-mavis-kids') return;
            return setHeader.call(this, name, value);
          };
          XMLHttpRequest.prototype.send = function(...args) {
            setHeader.call(this, 'x-mavis-kids', '1');
            return send.apply(this, args);
          };
          Object.defineProperty(navigator, 'share', {configurable: false, value: () => Promise.reject(new DOMException('Unavailable', 'NotAllowedError'))});
          Object.defineProperty(navigator, 'canShare', {configurable: false, value: () => false});
          Object.defineProperty(window, 'open', {configurable: false, value: () => null});
          for (const event of ['contextmenu', 'dragstart', 'selectstart'])
            document.addEventListener(event, e => e.preventDefault(), true);
          const selected = $selected;
          const allowed = h => { try { h = decodeURIComponent(h); } catch { return false; }
            return h === selected || (selected.startsWith('#/book/') && h === selected.replace('#/book/', '#/read/')); };
          window.addEventListener('hashchange', e => {
            if (!allowed(location.hash)) { e.stopImmediatePropagation(); location.replace(selected); }
          }, true);
          document.addEventListener('click', e => {
            const a = e.target.closest?.('a');
            if (a) { const u = new URL(a.href, location.href);
              if (u.origin !== location.origin || u.pathname !== '/' || !allowed(u.hash)) {
                e.preventDefault(); e.stopImmediatePropagation();
              }
            }
          }, true);
          const style = document.createElement('style');
          style.textContent = `
            html, body, * {user-select:none!important;-webkit-user-select:none!important;-webkit-touch-callout:none!important}
            .shell > :not(main), .skip-link, #toasts, .bible-bar, .bible-lookup, .chapter-nav,
            [data-act=ask], [data-act=companion], [data-act=club], [data-act=tts], [data-act=lookup],
            [data-act=display], [data-act=close], [data-close], [data-back], [data-want],
            [data-remove], [data-remove-file], #status, #lv, #ab, #bc, .sel-toolbar, .verse-bar,
            .credits, .facts, .attrib {display:none!important}
            .shell {display:block!important} .main {margin:0!important;padding:0!important}
            body {font-size:20px!important;background:#fff8e8!important}
            button,.btn,select {min-height:64px!important;min-width:64px!important;font-size:20px!important;border-radius:24px!important}
            h1 {font-size:32px!important} .reader-bar .bt {font-size:20px!important}
          `;
          const mount = () => { if (document.documentElement && !style.isConnected) document.documentElement.append(style); };
          new MutationObserver(mount).observe(document, {childList:true,subtree:true}); mount();
        })();
    """.trimIndent()
}
