package com.mavislibrary.app.web

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/** Attaches the shared WebView and shows [hashRoute] (e.g. "#/bible") as this tab's root. */
@Composable
fun WebScreen(host: WebHost, hashRoute: String, modifier: Modifier = Modifier) {
    LaunchedEffect(hashRoute) { host.show(hashRoute) }
    AndroidView(
        factory = {
            host.webView.apply {
                (parent as? ViewGroup)?.removeView(this)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
        modifier = modifier.testTag("web_host"),
    )
}
