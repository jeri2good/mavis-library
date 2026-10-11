package com.mavislibrary.app.web

import android.content.Context
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
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f)
                .build(),
        )
        isActive = true
    }

    override fun close() {
        session.isActive = false
        session.release()
    }
}
