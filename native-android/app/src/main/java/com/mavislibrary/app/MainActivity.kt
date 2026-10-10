package com.mavislibrary.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.ui.MavisRoot
import com.mavislibrary.app.ui.theme.MavisTheme
import com.mavislibrary.app.ui.theme.tokensFor
import com.mavislibrary.app.web.WebHost
import kotlinx.coroutines.delay
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    private var webHost: WebHost? = null

    /** Robolectric has no real Chromium, so the JVM shell tests run without the WebView. */
    private val useWebHost: Boolean get() = android.os.Build.FINGERPRINT != "robolectric"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MavisApp).container
        webHost = if (useWebHost) WebHost(this) else null

        setContent {
            val choice by container.settings.lookChoice.collectAsStateWithLifecycle(initialValue = null)
            val online by container.connectivity.online.collectAsStateWithLifecycle()
            val now by produceState(LocalTime.now()) {
                while (true) {
                    delay(60_000)
                    value = LocalTime.now()
                }
            }
            val resolved = choice?.resolve(now)
            if (resolved == null) {
                Box(Modifier.fillMaxSize().background(colorResource(R.color.window_background)))
                return@setContent
            }
            val dark = tokensFor(resolved).isDark
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MavisTheme(resolved) {
                MavisRoot(container, webHost, online)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webHost?.onResume()
    }

    override fun onPause() {
        webHost?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webHost?.destroy()
        webHost = null
        super.onDestroy()
    }
}
