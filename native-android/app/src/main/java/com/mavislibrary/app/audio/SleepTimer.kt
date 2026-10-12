package com.mavislibrary.app.audio

import kotlin.math.max

sealed interface SleepTarget {
    data class At(val elapsedRealtimeMs: Long) : SleepTarget
    data object EndOfChapter : SleepTarget
}

data class SleepTimerState(
    val target: SleepTarget? = null,
    val fading: Boolean = false,
) {
    val active: Boolean get() = target != null
}

sealed interface SleepTimerEffect {
    data object None : SleepTimerEffect
    data object BeginFade : SleepTimerEffect
    data object Pause : SleepTimerEffect
}

/** Pure state machine. The service supplies elapsed realtime, so wall-clock changes cannot move a timer. */
object SleepTimer {
    const val FADE_MS = 10_000L
    const val SHAKE_EXTENSION_MS = 10 * 60_000L

    fun afterMinutes(minutes: Int, nowMs: Long): SleepTimerState {
        require(minutes in 1..120)
        return SleepTimerState(SleepTarget.At(nowMs + minutes * 60_000L))
    }

    fun endOfChapter(): SleepTimerState = SleepTimerState(SleepTarget.EndOfChapter)

    fun cancel(): SleepTimerState = SleepTimerState()

    fun tick(state: SleepTimerState, nowMs: Long): Pair<SleepTimerState, SleepTimerEffect> {
        val at = state.target as? SleepTarget.At ?: return state to SleepTimerEffect.None
        val left = at.elapsedRealtimeMs - nowMs
        return when {
            left <= 0 -> SleepTimerState() to SleepTimerEffect.Pause
            left <= FADE_MS && !state.fading -> state.copy(fading = true) to SleepTimerEffect.BeginFade
            else -> state to SleepTimerEffect.None
        }
    }

    fun chapterEnded(state: SleepTimerState): Pair<SleepTimerState, SleepTimerEffect> =
        if (state.target == SleepTarget.EndOfChapter) SleepTimerState() to SleepTimerEffect.Pause
        else state to SleepTimerEffect.None

    fun extendFromShake(state: SleepTimerState, nowMs: Long): SleepTimerState {
        if (!state.active) return state
        val base = max(nowMs, (state.target as? SleepTarget.At)?.elapsedRealtimeMs ?: nowMs)
        return SleepTimerState(SleepTarget.At(base + SHAKE_EXTENSION_MS))
    }
}

object FadeMath {
    /** Linear gain from 1 to 0 over [durationMs], clamped for late/early scheduler ticks. */
    fun gain(elapsedMs: Long, durationMs: Long = SleepTimer.FADE_MS): Float {
        require(durationMs > 0)
        return (1f - elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
