package com.mavislibrary.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

data class KidsStoryState(
    val story: Story? = null, val directory: File? = null, val tier: String = "t2",
    val narrator: String = "host", val page: Int = 0, val playing: Boolean = false,
    val ended: Boolean = false, val loading: Boolean = false, val error: String? = null,
    val sleepMinutes: Int = 0,
) {
    val pages get() = story?.pagesFor(tier).orEmpty()
}

@androidx.annotation.OptIn(UnstableApi::class)
class KidsStoryService : MediaSessionService() {
    private lateinit var narration: ExoPlayer
    private lateinit var music: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var deadline: Long? = null
    private var pendingAdvance = false
    private var startJob: Job? = null
    private val advance = Runnable { if (pendingAdvance && narration.playWhenReady) next() }
    private val timer = object : Runnable {
        override fun run() {
            if (deadline?.let { SystemClock.elapsedRealtime() >= it } == true) {
                deadline = null
                pendingAdvance = false
                handler.removeCallbacks(advance)
                narration.pause()
                mutable.value = mutable.value.copy(sleepMinutes = 0)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        narration = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val s = mutable.value
                    val active = player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                        !s.ended && s.story != null && s.error == null
                    music.playWhenReady = active
                    music.volume = if (player.isPlaying) 0.045f else 0.14f
                    mutable.value = s.copy(playing = active)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && mutable.value.story != null) {
                        pendingAdvance = true
                        handler.removeCallbacks(advance)
                        handler.postDelayed(advance, 1000)
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady && pendingAdvance) {
                        handler.removeCallbacks(advance)
                        handler.postDelayed(advance, 1000)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    music.pause()
                    mutable.value = mutable.value.copy(playing = false, error = "Couldn't play this page. Try again.")
                }
            })
        }
        music = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0.14f
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Narration remains usable if the optional background soundtrack cannot decode.
                    pause()
                }
            })
        }
        val open = PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, narration).setSessionActivity(open).setCallback(Callback()).build()
        handler.post(timer)
    }

    private fun start(args: Bundle) {
        startJob?.cancel()
        stopStory()
        mutable.value = KidsStoryState(loading = true)
        startJob = scope.launch {
            try {
                require(kidsPrefs().state.first().enabled)
                val repository = StoryRepository(this@KidsStoryService)
                val catalog = withContext(Dispatchers.IO) { repository.cachedCatalog() } ?: error("No catalog")
                val entry = catalog.stories.single { it.id == args.getString("id") }
                val story = withContext(Dispatchers.IO) {
                    require(repository.installed(entry))
                    repository.load(entry)
                }
                val voice = args.getString("voice")?.takeIf { it in STORY_VOICES } ?: "host"
                val tier = storyTier(args.getInt("age", 5))
                require(story.pagesFor(tier).isNotEmpty()) { "No pages for this age" }
                mutable.value = KidsStoryState(story, repository.directory(entry), tier, voice)
                music.setMediaItem(MediaItem.fromUri(StoryPacks.file(repository.directory(entry), story.music).toURI().toString()))
                music.prepare()
                showPage(0)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = KidsStoryState(error = "This story isn't ready. Return to the shelf and try again.") }
        }
    }

    private fun showPage(index: Int) {
        handler.removeCallbacks(advance)
        pendingAdvance = false
        val s = mutable.value
        val page = s.pages.getOrNull(index) ?: return finishStory()
        val path = page.audio[s.narrator]?.get(s.tier)
        if (path == null) {
            narration.pause()
            music.pause()
            mutable.value = s.copy(page = index, playing = false, error = "This narrator isn't available for this page.")
            return
        }
        mutable.value = s.copy(page = index, ended = false, error = null)
        val item = MediaItem.Builder().setMediaId("${s.story!!.id}:$index")
            .setUri(StoryPacks.file(s.directory!!, path).toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.story.title).setArtist("Mavis Kids")
                .setSubtitle("Page ${index + 1}").setArtworkUri(android.net.Uri.fromFile(StoryPacks.file(s.directory, page.img))).build())
            .build()
        narration.setMediaItem(item)
        if (music.playbackState == Player.STATE_IDLE) music.prepare()
        narration.prepare()
        narration.play()
    }

    private fun next() = showPage(mutable.value.page + 1)
    private fun finishStory() {
        pendingAdvance = false
        handler.removeCallbacks(advance)
        narration.pause()
        music.stop()
        deadline = null
        mutable.value = mutable.value.copy(ended = true, playing = false, sleepMinutes = 0)
    }
    private fun stopStory() {
        pendingAdvance = false
        deadline = null
        handler.removeCallbacks(advance)
        narration.stop()
        narration.clearMediaItems()
        music.stop()
        music.clearMediaItems()
        mutable.value = KidsStoryState()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted &&
                !session.isMediaNotificationController(controller)) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                COMMANDS.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }.build()
            // Use the full default command set, not the empty Player.Commands.Builder trap.
            // External clients must never replace local story clips with arbitrary URIs.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands).setAvailablePlayerCommands(playerCommands).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            when (customCommand.customAction) {
                START -> start(args)
                NEXT -> next()
                PREVIOUS -> showPage((mutable.value.page - 1).coerceAtLeast(0))
                TOGGLE -> {
                    if (mutable.value.error != null) showPage(mutable.value.page)
                    else if (narration.playWhenReady) narration.pause() else narration.play()
                }
                STOP -> { startJob?.cancel(); stopStory() }
                SLEEP -> {
                    val minutes = args.getInt("minutes").takeIf { it == 10 || it == 20 } ?: 0
                    deadline = if (minutes == 0) null else SystemClock.elapsedRealtime() + minutes * 60_000L
                    mutable.value = mutable.value.copy(sleepMinutes = minutes)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        session.release()
        narration.release()
        music.release()
        mutable.value = KidsStoryState()
        super.onDestroy()
    }
    companion object {
        const val START = "kids.start"
        const val NEXT = "kids.next"
        const val PREVIOUS = "kids.previous"
        const val TOGGLE = "kids.toggle"
        const val STOP = "kids.stop"
        const val SLEEP = "kids.sleep"
        val COMMANDS = listOf(START, NEXT, PREVIOUS, TOGGLE, STOP, SLEEP)
        private val mutable = MutableStateFlow(KidsStoryState())
        val state = mutable.asStateFlow()
    }
}
