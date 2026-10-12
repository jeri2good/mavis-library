package com.mavislibrary.app.lock

import android.os.SystemClock

class LockPolicy(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = 60_000L,
) {
    enum class State { LOCKED, UNLOCKED, NEEDS_AUTH }

    var state: State = State.UNLOCKED
        private set
    private var enabled = false
    private var backgroundAt: Long? = null

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        state = if (value) State.LOCKED else State.UNLOCKED
        backgroundAt = null
    }

    fun authenticated() {
        if (enabled) state = State.UNLOCKED
    }

    fun background() {
        if (enabled && backgroundAt == null) backgroundAt = clock()
    }

    fun foreground() {
        val elapsed = backgroundAt?.let { clock() - it }
        backgroundAt = null
        if (enabled && elapsed != null && (elapsed < 0 || elapsed > timeoutMs)) state = State.NEEDS_AUTH
    }

    fun canShowAdult(kidsMode: Boolean): Boolean = kidsMode || state == State.UNLOCKED
}
