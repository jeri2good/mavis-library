package com.mavislibrary.app.ui.kids

import android.annotation.SuppressLint
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
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) = assets.shouldInterceptRequest(r.url)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                    r.isForMainFrame && !kidsWebRouteAllowed(r.url.toString(), route)
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
