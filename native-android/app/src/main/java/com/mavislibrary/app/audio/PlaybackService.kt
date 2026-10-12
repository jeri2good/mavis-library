package com.mavislibrary.app.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.MavisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object AudioCommands {
    const val SLEEP_MINUTES = "mavis.sleep.minutes"
    const val SLEEP_15 = "mavis.sleep.15"
    const val SLEEP_30 = "mavis.sleep.30"
    const val SLEEP_45 = "mavis.sleep.45"
    const val SLEEP_60 = "mavis.sleep.60"
    const val SLEEP_CHAPTER = "mavis.sleep.chapter"
    const val SLEEP_CANCEL = "mavis.sleep.cancel"
    const val SLEEP_EXTEND = "mavis.sleep.extend"
    const val SEEK_BACK = "mavis.seek.back15"
    const val SEEK_FORWARD = "mavis.seek.forward30"
    val all = setOf(SLEEP_MINUTES, SLEEP_15, SLEEP_30, SLEEP_45, SLEEP_60, SLEEP_CHAPTER, SLEEP_CANCEL, SLEEP_EXTEND, SEEK_BACK, SEEK_FORWARD)
}

/**
 * One long-lived player for UI, lock screen, Bluetooth/headset buttons and future Android Auto.
 * ExoPlayer owns audio focus and pauses automatically when audio becomes noisy.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var librarySession: MediaLibraryService.MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var sleepState = SleepTimerState()
    private var fadeStartedAt: Long? = null
    private var lastSavedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (sleepState.target == SleepTarget.EndOfChapter && fadeStartedAt == null &&
                player.duration > 0 && player.duration - player.currentPosition in 1..SleepTimer.FADE_MS
            ) fadeStartedAt = now
            val (next, effect) = SleepTimer.tick(sleepState, now)
            sleepState = next
            when (effect) {
                SleepTimerEffect.BeginFade -> fadeStartedAt = now
                SleepTimerEffect.Pause -> finishSleep()
                SleepTimerEffect.None -> Unit
            }
            fadeStartedAt?.let { started -> player.volume = FadeMath.gain(now - started) }
            if (player.isPlaying && now - lastSavedAt >= 5_000) {
                lastSavedAt = now
                savePosition()
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(PodcastDataSourceFactory(this, (application as MavisApp).container.podcastSettings))
                    // archive.org's CDN sometimes answers 500/503 for a moment; retry patiently instead of failing the book.
                    .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)),
            )
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val (next, effect) = SleepTimer.chapterEnded(sleepState)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && effect == SleepTimerEffect.Pause) {
                        sleepState = next
                        finishSleep()
                    }
                    publishSleepButton()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) savePosition() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = savePosition()
                override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) savePosition() }
            })
        }
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(activityIntent)
            .build()
        publishSleepButton()
        handler.post(ticker)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession = librarySession

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        savePosition()
        scope.cancel()
        librarySession.release()
        player.release()
        super.onDestroy()
    }

    private fun finishSleep() {
        player.volume = 1f
        fadeStartedAt = null
        player.pause()
        publishSleepButton()
    }

    private fun savePosition() {
        val item = player.currentMediaItem ?: return
        val bookKey = item.mediaMetadata.extras?.getString(EXTRA_BOOK_KEY) ?: return
        val value = ResumePosition(bookKey, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition, player.playbackParameters.speed)
        val app = application as MavisApp
        scope.launch { app.container.resumePositionStore.save(value) }
    }

    private fun sleepLabel(): String = when (val target = sleepState.target) {
        null -> "Sleep timer"
        SleepTarget.EndOfChapter -> "Sleep: chapter"
        is SleepTarget.At -> "Sleep: ${((target.elapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60_000) + 1} min"
    }

    private fun publishSleepButton() {
        val command = SessionCommand(AudioCommands.SLEEP_30, Bundle.EMPTY)
        librarySession.setCustomLayout(
            ImmutableList.of(
                CommandButton.Builder(CommandButton.ICON_SETTINGS)
                    .setDisplayName(sleepLabel())
                    .setSessionCommand(command)
                    .build(),
            ),
        )
    }

    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event != null && com.mavislibrary.app.voice.VoiceHeadset.handle(event) {
                    if (player.playWhenReady) player.pause() else { player.prepare(); player.play() }
                }) return true
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val commands = base.availableSessionCommands.buildUpon().apply {
                AudioCommands.all.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(
                MediaMetadata.Builder().setTitle("Mavis Library").setIsBrowsable(true).setIsPlayable(false).build(),
            ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                AudioCommands.SLEEP_MINUTES -> {
                    val minutes = args.getInt("minutes")
                    if (minutes !in 1..120) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    sleepState = SleepTimer.afterMinutes(minutes, SystemClock.elapsedRealtime())
                }
                AudioCommands.SLEEP_15 -> sleepState = SleepTimer.afterMinutes(15, SystemClock.elapsedRealtime())
                AudioCommands.SLEEP_30 -> sleepState = SleepTimer.afterMinutes(30, SystemClock.elapsedRealtime())
                AudioCommands.SLEEP_45 -> sleepState = SleepTimer.afterMinutes(45, SystemClock.elapsedRealtime())
                AudioCommands.SLEEP_60 -> sleepState = SleepTimer.afterMinutes(60, SystemClock.elapsedRealtime())
                AudioCommands.SLEEP_CHAPTER -> sleepState = SleepTimer.endOfChapter()
                AudioCommands.SLEEP_CANCEL -> sleepState = SleepTimer.cancel()
                AudioCommands.SLEEP_EXTEND -> sleepState = SleepTimer.extendFromShake(sleepState, SystemClock.elapsedRealtime())
                AudioCommands.SEEK_BACK -> player.seekBack()
                AudioCommands.SEEK_FORWARD -> player.seekForward()
            }
            fadeStartedAt = null
            player.volume = 1f
            publishSleepButton()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        const val ROOT_ID = "mavis_root"
        const val EXTRA_BOOK_KEY = "mavis.book_key"
    }
}
