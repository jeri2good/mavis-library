package com.mavislibrary.app.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.audio.PlaybackService
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit, immutable PendingIntent to a private receiver; controls the existing Media3 service. */
class PodcastPlaybackAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(PLAY, TOGGLE)) return
        val id = intent.getStringExtra("job") ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var controller: MediaController? = null
            try {
                withTimeout(8000) {
                    val c = (context.applicationContext as MavisApp).container
                    if (context.kidsPrefs().state.first().enabled) return@withTimeout
                    val account = c.podcastAccountId() ?: return@withTimeout
                    val saved = c.podcastSettings.library.first().find { it.job.id == id && it.accountId == account && it.job.status == "ready" } ?: return@withTimeout
                    val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
                    val player = suspendCancellableCoroutine<MediaController> { continuation ->
                        Futures.addCallback(future, object : FutureCallback<MediaController> {
                            override fun onSuccess(value: MediaController) { if (continuation.isActive) continuation.resume(value) else value.release() }
                            override fun onFailure(t: Throwable) { if (continuation.isActive) continuation.resumeWithException(t) }
                        }, androidx.core.content.ContextCompat.getMainExecutor(context))
                        continuation.invokeOnCancellation { future.cancel(true) }
                    }
                    controller = player
                    if (context.kidsPrefs().state.first().enabled || c.podcastAccountId() != account) return@withTimeout
                    when (playbackAction(intent.action == TOGGLE, player.currentMediaItem?.mediaId == saved.resumeKey, player.isPlaying)) {
                        "pause" -> player.pause()
                        "resume" -> { player.prepare(); player.play() }
                        else -> {
                            val local = c.audioFiles.podcastLocal(saved.studio, id)
                            if (local == null && saved.studio != com.mavislibrary.app.BuildConfig.API_BASE) return@withTimeout
                            val uri = local?.let(Uri::fromFile) ?: Uri.parse(c.podcastApi().audioUrl(id))
                            val resume = c.resumePositionStore.load(saved.resumeKey)
                            val item = MediaItem.Builder().setMediaId(saved.resumeKey).setUri(uri).setMimeType("audio/mpeg")
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(saved.job.title).setArtist("Mavis Podcast Lounge")
                                    .setExtras(Bundle().apply { putString(PlaybackService.EXTRA_BOOK_KEY, saved.resumeKey) }).build()).build()
                            player.setMediaItem(item, resume?.positionMs ?: 0); player.prepare(); player.play()
                        }
                    }
                }
            } catch (_: Exception) { /* A removed episode or unavailable service does not launch a blank screen. */ }
            finally { controller?.release(); result.finish() }
        }
    }
    companion object {
        const val PLAY = "com.mavislibrary.app.PODCAST_PLAY"
        const val TOGGLE = "com.mavislibrary.app.PODCAST_TOGGLE"
        fun pending(context: Context, id: String, toggle: Boolean): PendingIntent {
            val action = if (toggle) TOGGLE else PLAY
            val intent = Intent(context, PodcastPlaybackAction::class.java).setAction(action).putExtra("job", id)
                .setData(Uri.parse("mavis://podcast-action/${Uri.encode(id)}/$toggle"))
            return PendingIntent.getBroadcast(context, (id + action).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
fun playbackAction(toggle: Boolean, sameEpisode: Boolean, playing: Boolean) = if (!sameEpisode) "load" else if (toggle && playing) "pause" else "resume"
