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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.R
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
    val longBook: LongListenBook? = null, val positionMs: Long = 0,
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
    private var saveJob: Job? = null
    private var lastPositionSave = 0L
    private val container get() = (application as MavisApp).container
    private val advance = Runnable { if (pendingAdvance && narration.playWhenReady) next() }
    private val timer = object : Runnable {
        override fun run() {
            if (mutable.value.longBook != null) {
                mutable.value = mutable.value.copy(positionMs = narration.currentPosition.coerceAtLeast(0))
                if (SystemClock.elapsedRealtime() - lastPositionSave >= 5_000) {
                    lastPositionSave = SystemClock.elapsedRealtime()
                    saveLongPosition()
                }
            }
            if (deadline?.let { SystemClock.elapsedRealtime() >= it } == true) {
                deadline = null
                handler.removeCallbacks(advance)
                narration.pause()
                mutable.value = mutable.value.copy(sleepMinutes = 0)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Adult playback keeps Media3's default ID/channel; Kids must not replace or cancel it.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1002)
            .setChannelId("kids_story_playback")
            .setChannelName(R.string.kids_playback_channel)
            .build())
        narration = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val s = mutable.value
                    val active = player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                        player.playbackState != Player.STATE_IDLE &&
                        (s.longBook == null || player.playbackState != Player.STATE_ENDED) &&
                        !s.ended && (s.story != null || s.longBook != null) && s.error == null
                    music.playWhenReady = active && s.story != null
                    music.volume = if (player.isPlaying) 0.045f else 0.14f
                    mutable.value = s.copy(playing = active)
                    if (!player.playWhenReady) saveLongPosition()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && mutable.value.longBook != null) {
                        finishChapter()
                        return
                    }
                    if (playbackState == Player.STATE_ENDED && narration.playbackState == Player.STATE_ENDED &&
                        mutable.value.story != null) {
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
                    mutable.value = mutable.value.copy(playing = false, error = "Couldn't play this audio. Try again.")
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
        session = MediaSession.Builder(this, narration).setId("kids-story").setSessionActivity(open).setCallback(Callback()).build()
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
                val voice = catalog.narrator(args.getInt("age", 5), args.getString("voice"))
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

    private fun saveLongPosition() {
        val s = mutable.value
        val book = s.longBook ?: return
        if (narration.currentMediaItem?.mediaId != "${book.id}:${s.page}") return
        val value = ResumePosition("kids-long:${book.id}", s.page, narration.currentPosition.coerceAtLeast(0), 1f)
        val previous = saveJob
        // The application scope survives service teardown, and writes retain snapshot order.
        saveJob = container.scope.launch {
            previous?.join()
            container.resumePositionStore.save(value)
        }
    }

    private fun startLong(args: Bundle) {
        startJob?.cancel()
        stopStory()
        mutable.value = KidsStoryState(loading = true)
        startJob = scope.launch {
            try {
                require(kidsPrefs().state.first().enabled)
                val catalog = withContext(Dispatchers.IO) { StoryRepository(this@KidsStoryService).cachedLongListen() }
                    ?: error("No Long Listen catalog")
                val book = catalog.books.single { it.id == args.getString("id") }
                saveJob?.join()
                val resume = container.resumePositionStore.load("kids-long:${book.id}")
                val chapter = if (args.containsKey("chapter")) args.getInt("chapter") else resume?.chapterIndex ?: 0
                require(chapter in book.sections.indices)
                mutable.value = KidsStoryState(longBook = book)
                showChapter(chapter, if (!args.containsKey("chapter")) resume?.positionMs ?: 0 else 0)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = KidsStoryState(error = "This audiobook isn't ready. Return to Long Listen and try again.") }
        }
    }

    private fun showChapter(index: Int, position: Long = 0) {
        val s = mutable.value
        val book = s.longBook ?: return
        val chapter = book.sections.getOrNull(index) ?: return finishStory()
        saveLongPosition()
        // Stop before changing the snapshot so listener events cannot label old audio as a new chapter.
        narration.stop()
        mutable.value = s.copy(page = index, positionMs = position, ended = false, error = null)
        narration.setMediaItem(MediaItem.Builder().setMediaId("${book.id}:$index")
            .setUri(container.audioFiles.playableUri(book.version().id, index, chapter.mp3))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(chapter.title).setArtist(book.title)
                .setArtworkUri(android.net.Uri.parse(book.cover)).build()).build(), position.coerceAtLeast(0))
        narration.prepare()
        narration.play()
    }

    private fun finishChapter() {
        if (mutable.value.sleepMinutes == -1) {
            narration.pause()
            mutable.value = mutable.value.copy(sleepMinutes = 0, playing = false)
        } else next()
    }

    private fun showPage(index: Int) {
        handler.removeCallbacks(advance)
        pendingAdvance = false
        val s = mutable.value
        if (s.story == null) return
        val page = s.pages.getOrNull(index) ?: return finishStory()
        val path = page.clip(s.narrator, s.tier)
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

    private fun next() {
        if (mutable.value.longBook != null) showChapter(mutable.value.page + 1)
        else showPage(mutable.value.page + 1)
    }
    private fun finishStory() {
        pendingAdvance = false
        handler.removeCallbacks(advance)
        narration.pause()
        music.stop()
        deadline = null
        mutable.value = mutable.value.copy(ended = true, playing = false, sleepMinutes = 0)
    }
    private fun stopStory() {
        saveLongPosition()
        pendingAdvance = false
        deadline = null
        handler.removeCallbacks(advance)
        mutable.value = KidsStoryState()
        narration.stop()
        narration.clearMediaItems()
        music.stop()
        music.clearMediaItems()
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
                START_LONG -> startLong(args)
                NEXT -> next()
                PREVIOUS -> if (mutable.value.longBook != null) showChapter((mutable.value.page - 1).coerceAtLeast(0))
                    else showPage((mutable.value.page - 1).coerceAtLeast(0))
                TOGGLE -> {
                    if (mutable.value.longBook != null &&
                        (mutable.value.error != null || narration.playbackState in listOf(Player.STATE_IDLE, Player.STATE_ENDED))) {
                        val s = mutable.value
                        val nextChapter = if (narration.playbackState == Player.STATE_ENDED && s.sleepMinutes != -1)
                            s.page + 1 else s.page
                        showChapter(nextChapter.coerceAtMost(s.longBook!!.sections.lastIndex),
                            if (mutable.value.error != null) s.positionMs else 0)
                    }
                    else if (mutable.value.error != null || narration.playbackState == Player.STATE_IDLE) showPage(mutable.value.page)
                    else if (narration.playWhenReady) narration.pause() else narration.play()
                }
                STOP -> { startJob?.cancel(); stopStory() }
                SLEEP -> {
                    val allowed = if (mutable.value.longBook != null) listOf(-1, 15, 30, 45, 60) else listOf(10, 20)
                    val minutes = args.getInt("minutes").takeIf { it in allowed } ?: 0
                    deadline = if (minutes <= 0) null else SystemClock.elapsedRealtime() + minutes * 60_000L
                    mutable.value = mutable.value.copy(sleepMinutes = minutes)
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onDestroy() {
        saveLongPosition()
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
        const val START_LONG = "kids.long.start"
        const val NEXT = "kids.next"
        const val PREVIOUS = "kids.previous"
        const val TOGGLE = "kids.toggle"
        const val STOP = "kids.stop"
        const val SLEEP = "kids.sleep"
        val COMMANDS = listOf(START, START_LONG, NEXT, PREVIOUS, TOGGLE, STOP, SLEEP)
        private val mutable = MutableStateFlow(KidsStoryState())
        val state = mutable.asStateFlow()
    }
}
