package com.mavislibrary.app.data.stats

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.mavislibrary.app.audio.PlaybackService

/** Read-only session observer. Stable book keys deduplicate listening and reading the same book. */
class StatsAudioSource(context: Context, private val changed: () -> Unit) : AutoCloseable {
    var bookKey: String? = null
        private set
    var playing: Boolean = false
        private set
    private var closed = false
    private var controller: MediaController? = null
    private fun refresh() {
        playing = controller?.isPlaying == true
        bookKey = controller?.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.EXTRA_BOOK_KEY)
        changed()
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val future = if (android.os.Build.FINGERPRINT == "robolectric") null else MediaController.Builder(context.applicationContext,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    init {
        future?.let { pending -> Futures.addCallback(pending, object : FutureCallback<MediaController> {
            override fun onSuccess(result: MediaController) {
                if (closed) return
                controller = result
                result.addListener(listener)
                refresh()
            }
            override fun onFailure(t: Throwable) { playing = false; bookKey = null; changed() }
        }, MoreExecutors.directExecutor()) }
    }
    override fun close() {
        closed = true
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
    }
}
