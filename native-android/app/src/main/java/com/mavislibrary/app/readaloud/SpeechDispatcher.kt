package com.mavislibrary.app.readaloud

/** One queued SpeechSynthesisUtterance. */
data class SpeechRequest(
    val id: Long,
    val text: String,
    val voiceUri: String? = null,
    val lang: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
)

interface SpeechCallback {
    fun onStart()
    fun onBoundary(name: String, charIndex: Int, charLength: Int)
    fun onDone()
    fun onError(code: String)
}

/** A voice engine plays one request at a time. Callbacks may arrive on any thread. */
interface SpeechEngine {
    fun speak(request: SpeechRequest, callback: SpeechCallback)
    fun stop()
    /** True when paused in place. False means the engine stopped, so the dispatcher replays from the last boundary. */
    fun pause(): Boolean
    fun resume()
}

sealed interface SpeechEvent {
    val id: Long
    data class Start(override val id: Long) : SpeechEvent
    data class Boundary(override val id: Long, val name: String, val charIndex: Int, val charLength: Int) : SpeechEvent
    data class End(override val id: Long) : SpeechEvent
    data class Error(override val id: Long, val code: String) : SpeechEvent
    data class Pause(override val id: Long) : SpeechEvent
    data class Resume(override val id: Long) : SpeechEvent
}

/**
 * The spec's speech queue: speak appends and one request plays at a time. Cancel matches Chrome desktop: the current
 * request errors "interrupted", queued ones error "canceled", no `end` fires, and `paused` clears.
 * Late callbacks from a stopped engine are ignored, and an engine that fails before speaking falls back once.
 */
class SpeechDispatcher(
    private val route: (SpeechRequest) -> SpeechEngine,
    private val fallback: (SpeechEngine) -> SpeechEngine?,
    private val emit: (SpeechEvent) -> Unit,
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    private class Active(val request: SpeechRequest, var engine: SpeechEngine) {
        var attempt = 0
        var base = 0
        var lastBoundary = 0
        var started = false
        var fellBack = false
        var pausedInPlace = false
        var stoppedForPause = false
    }

    private val lock = Any()
    private val queue = ArrayDeque<SpeechRequest>()
    private var current: Active? = null
    private var paused = false
    private var active = false

    fun speak(request: SpeechRequest) = synchronized(lock) {
        queue.addLast(request)
        pump()
    }

    fun cancel(currentError: String = "interrupted") = synchronized(lock) {
        val playing = current
        current = null
        paused = false
        val waiting = queue.toList()
        queue.clear()
        if (playing != null) {
            playing.engine.stop()
            emit(SpeechEvent.Error(playing.request.id, currentError))
        }
        waiting.forEach { emit(SpeechEvent.Error(it.id, "canceled")) }
        updateActive()
    }

    /** Drops everything without events, for a page that has gone away. */
    fun reset() = synchronized(lock) {
        current?.engine?.stop()
        current = null
        paused = false
        queue.clear()
        updateActive()
    }

    fun pause() = synchronized(lock) {
        if (paused) return@synchronized
        paused = true
        current?.let { playing ->
            if (playing.engine.pause()) playing.pausedInPlace = true
            else {
                playing.stoppedForPause = true
                playing.attempt++
            }
            if (playing.started) emit(SpeechEvent.Pause(playing.request.id))
        }
    }

    fun resume() = synchronized(lock) {
        if (!paused) return@synchronized
        paused = false
        val playing = current
        when {
            playing == null -> pump()
            playing.pausedInPlace -> {
                playing.pausedInPlace = false
                playing.engine.resume()
                if (playing.started) emit(SpeechEvent.Resume(playing.request.id))
            }
            playing.stoppedForPause -> {
                playing.stoppedForPause = false
                playing.base = playing.lastBoundary
                if (playing.started) emit(SpeechEvent.Resume(playing.request.id))
                begin(playing)
            }
        }
    }

    private fun pump() {
        if (current == null && !paused) {
            val next = queue.removeFirstOrNull()
            if (next != null) {
                if (next.text.isBlank()) {
                    emit(SpeechEvent.Start(next.id))
                    emit(SpeechEvent.End(next.id))
                    pump()
                    return
                }
                val playing = Active(next, route(next))
                current = playing
                updateActive()
                begin(playing)
                return
            }
        }
        updateActive()
    }

    private fun updateActive() {
        val now = current != null || queue.isNotEmpty()
        if (now != active) {
            active = now
            onActiveChanged(now)
        }
    }

    private fun begin(playing: Active) {
        val attempt = ++playing.attempt
        val request = if (playing.base == 0) playing.request else playing.request.copy(text = playing.request.text.substring(playing.base))
        val base = playing.base
        fun guarded(block: () -> Unit) = synchronized(lock) {
            if (current === playing && playing.attempt == attempt) block()
        }
        playing.engine.speak(request, object : SpeechCallback {
            override fun onStart() = guarded {
                if (!playing.started) {
                    playing.started = true
                    emit(SpeechEvent.Start(playing.request.id))
                }
            }

            override fun onBoundary(name: String, charIndex: Int, charLength: Int) = guarded {
                playing.lastBoundary = base + charIndex
                emit(SpeechEvent.Boundary(playing.request.id, name, base + charIndex, charLength))
            }

            override fun onDone() = guarded {
                current = null
                emit(SpeechEvent.End(playing.request.id))
                pump()
            }

            override fun onError(code: String) = guarded {
                val other = if (playing.started || playing.fellBack) null else fallback(playing.engine)
                if (other != null) {
                    playing.fellBack = true
                    playing.engine = other
                    begin(playing)
                } else {
                    current = null
                    emit(SpeechEvent.Error(playing.request.id, code))
                    pump()
                }
            }
        })
    }
}

/** Sentence spans for per-sentence synthesis; long sentences break at a space so no chunk stalls the voice. */
fun splitSentences(text: String, maxLength: Int = 260): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    fun add(start: Int, endExclusive: Int) {
        var s = start
        var e = endExclusive
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e) return
        if ((s until e).none { text[it].isLetterOrDigit() }) return
        var from = s
        while (e - from > maxLength) {
            val space = text.lastIndexOf(' ', from + maxLength)
            val cut = if (space > from) space else from + maxLength
            spans += from until cut
            from = cut
            while (from < e && text[from].isWhitespace()) from++
        }
        if (from < e) spans += from until e
    }
    val closers = "\"'”’)]"
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') {
            add(start, i)
            start = i + 1
        } else if (c == '.' || c == '!' || c == '?' || c == '…') {
            var end = i + 1
            while (end < text.length && (text[end] == '.' || text[end] == '!' || text[end] == '?' || text[end] == '…' || text[end] in closers)) end++
            if (end == text.length || text[end].isWhitespace()) {
                add(start, end)
                start = end
                i = end
                continue
            }
        }
        i++
    }
    add(start, text.length)
    return spans
}
