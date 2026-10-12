package com.mavislibrary.app.voice

import android.view.KeyEvent

/** Registered only by the resumed adult UI. The service cannot start a microphone itself. */
object VoiceHeadset {
    var start: (() -> Boolean)? = null
        set(value) { field = value; gesture.reset() }
    private val gesture = HeadsetGesture()
    fun handle(event: KeyEvent, shortPress: () -> Unit): Boolean {
        val trigger = start ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        return gesture.handle(event.action == KeyEvent.ACTION_DOWN, event.action == KeyEvent.ACTION_UP,
            event.eventTime - event.downTime, event.isLongPress, event.isCanceled,
            trigger = { trigger() }, shortPress = shortPress)
    }
}

class HeadsetGesture {
    private var down = false
    private var fired = false
    fun reset() { down = false; fired = false }
    fun handle(isDown: Boolean, isUp: Boolean, heldMs: Long, longPress: Boolean, canceled: Boolean = false,
               trigger: () -> Unit, shortPress: () -> Unit): Boolean {
        if (!isDown && !isUp) return false
        if (isDown) {
            down = true
            if (!fired && (longPress || heldMs >= 500)) { fired = true; trigger() }
        } else {
            if (!canceled && !fired && (longPress || heldMs >= 500)) trigger()
            else if (!canceled && down && !fired) shortPress()
            reset()
        }
        return true
    }
}
