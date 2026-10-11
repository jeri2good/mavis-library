package com.mavislibrary.app.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/** Android hands earbud keys to this receiver; it forwards them to the reader while the reader is open. */
class ReaderMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action == KeyEvent.ACTION_DOWN) handler?.invoke(event.keyCode)
    }

    companion object {
        @Volatile var handler: ((Int) -> Boolean)? = null
    }
}
