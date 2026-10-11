package com.mavislibrary.app.web

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Android delivers earbud next/previous only to an ACTIVE media session. While the reader is on screen
 * and no audiobook is loaded, the app holds a small paused session so those keys turn pages.
 * Released as soon as the activity pauses; an audiobook session takes priority when it is playing.
 */
class ReaderMediaSession(context: Context, private val onPageKey: (Int) -> Boolean) : AutoCloseable {
    private val session = MediaSession(context, "MavisReaderKeys").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() { onPageKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
            override fun onSkipToPrevious() { onPageKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            override fun onPlay() { /* nothing plays; the key is swallowed so it cannot start other apps */ }
            override fun onPause() { }
        })
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setMediaButtonReceiver(
            PendingIntent.getBroadcast(
                context, 0,
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(context, ReaderMediaButtonReceiver::class.java)),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        ReaderMediaButtonReceiver.handler = onPageKey
        isActive = true
    }

    private fun state(state: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(state, 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
        .build()

    init {
        // Android only picks a session for media buttons when it CHANGES to playing after it is active.
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
    }


    override fun close() {
        ReaderMediaButtonReceiver.handler = null
        session.isActive = false
        session.release()
    }
}
