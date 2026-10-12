package com.mavislibrary.app.audio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import com.mavislibrary.app.data.api.LibriVoxVersion
import com.mavislibrary.app.data.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val chapter: String = "",
    val artwork: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepLabel: String = "Sleep timer",
)

class PlaybackController(context: Context, connect: Boolean = android.os.Build.FINGERPRINT != "robolectric") : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private val future: ListenableFuture<MediaController>? = if (connect) MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync() else null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        future?.let { pending ->
            Futures.addCallback(pending, object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) {
                    controller = result
                    result.addListener(listener)
                    refresh()
                    scope.launch { while (isActive) { refresh(); delay(500) } }
                }
                override fun onFailure(t: Throwable) { _state.value = PlaybackUiState() }
            }, MoreExecutors.directExecutor())
        }
    }

    fun play(book: Book, version: LibriVoxVersion, files: AudioFileStore, resume: ResumePosition?) {
        val player = controller ?: run { android.util.Log.w("MavisAudio", "play(): player service not connected yet"); return }
        android.util.Log.i("MavisAudio", "play(): ${version.sections.size} chapters, commands ok=${player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)}")
        val items = version.sections.mapIndexed { index, section ->
            val extras = Bundle().apply { putString(PlaybackService.EXTRA_BOOK_KEY, book.key) }
            MediaItem.Builder()
                .setMediaId("${version.id}:$index")
                .setUri(files.playableUri(version.id, index, section.url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(version.readers.joinToString().ifBlank { section.reader })
                        .setSubtitle(section.title)
                        .setArtworkUri(book.coverUrl?.let(android.net.Uri::parse))
                        .setExtras(extras)
                        .setIsPlayable(true)
                        .build(),
                ).build()
        }
        player.setMediaItems(items, resume?.chapterIndex ?: 0, resume?.positionMs ?: 0)
        player.playbackParameters = PlaybackParameters(resume?.speed ?: 1f)
        player.prepare()
        player.play()
    }

    fun playPodcast(saved: com.mavislibrary.app.data.podcast.SavedPodcast, uri: android.net.Uri, resume: ResumePosition?) {
        val player = controller ?: return
        val extras = Bundle().apply { putString(PlaybackService.EXTRA_BOOK_KEY, saved.resumeKey) }
        val item = MediaItem.Builder().setMediaId(saved.resumeKey).setUri(uri).setMimeType("audio/mpeg")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(saved.job.title).setArtist("Mavis Podcast Lounge")
                .setSubtitle(saved.job.engineLabel).setArtworkData(PodcastArtwork.create(saved.job.title), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setExtras(extras).setIsPlayable(true).build()).build()
        player.setMediaItems(listOf(item), 0, resume?.positionMs ?: 0)
        player.playbackParameters = PlaybackParameters(resume?.speed ?: 1f)
        player.prepare(); player.play()
    }

    fun resume() { controller?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun pause() { controller?.pause() }
    fun stop() { controller?.stop() }
    fun sleepMinutes(minutes: Int) {
        require(minutes in 1..120)
        controller?.sendCustomCommand(SessionCommand(AudioCommands.SLEEP_MINUTES, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) })
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(ms: Long) { controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) } }
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
    fun chapter(index: Int) { controller?.seekToDefaultPosition(index) }
    fun speed(value: Float) { controller?.playbackParameters = PlaybackParameters(value.coerceIn(.75f, 2f)) }
    fun command(action: String) { controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun refresh() {
        val p = controller ?: return
        val metadata = p.currentMediaItem?.mediaMetadata
        _state.value = PlaybackUiState(
            connected = true,
            playing = p.isPlaying,
            title = metadata?.title?.toString().orEmpty(),
            chapter = metadata?.subtitle?.toString().orEmpty(),
            artwork = metadata?.artworkUri?.toString(),
            chapterIndex = p.currentMediaItemIndex.coerceAtLeast(0),
            chapterCount = p.mediaItemCount,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.coerceAtLeast(0),
            speed = p.playbackParameters.speed,
            sleepLabel = p.customLayout.firstOrNull {
                it.sessionCommand?.customAction == AudioCommands.SLEEP_30
            }?.displayName?.toString() ?: "Sleep timer",
        )
    }

    override fun close() {
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        scope.cancel()
    }
}
