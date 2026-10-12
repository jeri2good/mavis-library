package com.mavislibrary.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.podcast.articleUrl
import com.mavislibrary.app.data.podcast.loungeRouteAllowed

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleScreen(source: PodcastDraft, onBack: () -> Unit, onPodcast: (PodcastDraft) -> Unit) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (!loungeRouteAllowed(kids?.enabled)) return
    var current by remember(source) { mutableStateOf(source) }
    var canGoBack by remember(source) { mutableStateOf(false) }
    fun external(url: String) {
        if (url.toUri().scheme !in setOf("https", "http")) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { }
    }
    val view = remember(source.url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setGeolocationEnabled(false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    runCatching { articleUrl(request.url.toString()) }.isFailure
                override fun onPageFinished(view: WebView, url: String?) {
                    val safe = url?.let { runCatching { articleUrl(it) }.getOrNull() } ?: return
                    current = PodcastDraft.article(view.title.orEmpty().ifBlank { source.title }, safe)
                    canGoBack = view.canGoBack()
                }
            }
            setDownloadListener { _, _, _, _, _ -> }
            runCatching { articleUrl(source.url) }.getOrNull()?.let(::loadUrl)
        }
    }
    DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
    BackHandler(canGoBack) { view.goBack() }
    Column(Modifier.fillMaxSize().testTag("article_reader")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Button(onClick = { onPodcast(current) }, modifier = Modifier.testTag("article_podcast")) { Text("Make podcast of this article") }
            TextButton(onClick = { external(current.url) }) { Text("Open original in browser") }
        }
        if (runCatching { articleUrl(source.url) }.isFailure) Text("This site needs an HTTPS address for podcasts. You can still open the original in your browser.", Modifier.padding(16.dp))
        else AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
