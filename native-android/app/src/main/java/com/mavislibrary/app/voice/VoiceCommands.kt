package com.mavislibrary.app.voice

import com.mavislibrary.app.data.kids.adultRoutesAllowed
import java.util.Locale

sealed interface Command {
    data object Play : Command
    data object Pause : Command
    data object Stop : Command
    data class Chapter(val delta: Int) : Command
    data class Seek(val milliseconds: Long) : Command
    data class Timer(val minutes: Int) : Command
    data object CancelTimer : Command
    data class Speed(val value: Float, val relative: Boolean = false) : Command
    data object Bookmark : Command
    data class Page(val next: Boolean) : Command
}

/** Whole utterances only: never search for a command inside conversational speech. */
fun VoiceCommandParser(text: String): Command? {
    if (text.length > 160) return null
    val words = text.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',').split(Regex("\\s+")).map { it.trim(',') }.toMutableList()
    if (words.size > 8) return null
    if (words.take(2) == listOf("hey", "mavis")) repeat(2) { words.removeAt(0) }
    if (words.firstOrNull() == "please") words.removeAt(0)
    if (words.lastOrNull() == "please") words.removeAt(words.lastIndex)
    val phrase = words.joinToString(" ")
    when (phrase) {
        "play", "resume", "play audio", "resume playback" -> return Command.Play
        "pause", "pause audio" -> return Command.Pause
        "stop", "stop playback" -> return Command.Stop
        "next chapter" -> return Command.Chapter(1)
        "previous chapter" -> return Command.Chapter(-1)
        "cancel timer", "cancel sleep timer" -> return Command.CancelTimer
        "speed up", "faster" -> return Command.Speed(.25f, true)
        "slower", "slow down" -> return Command.Speed(-.25f, true)
        "bookmark this" -> return Command.Bookmark
        "next page" -> return Command.Page(true)
        "previous page" -> return Command.Page(false)
    }
    Regex("(?:speed )?(0\\.75|1|1\\.0|1\\.25|1\\.5|1\\.75|2|2\\.0)[x×]").matchEntire(phrase)?.let {
        return Command.Speed(it.groupValues[1].toFloat())
    }
    Regex("(back|forward|skip) (a|one|15|fifteen|30|thirty|60|sixty) (seconds?|minutes?)").matchEntire(phrase)?.let {
        val n = number(it.groupValues[2]) ?: return null
        val amount = n * if (it.groupValues[3].startsWith("minute")) 60_000L else 1_000L
        if (amount > 300_000) return null
        return Command.Seek(if (it.groupValues[1] == "back") -amount else amount)
    }
    Regex("sleep timer (\\d{1,3}|fifteen|twenty|thirty|forty five|sixty) minutes?").matchEntire(phrase)?.let {
        val n = number(it.groupValues[1]) ?: return null
        if (n in 1..120) return Command.Timer(n)
    }
    return null
}

private fun number(word: String): Int? = when (word) {
    "a", "one" -> 1
    "fifteen" -> 15
    "twenty" -> 20
    "thirty" -> 30
    "forty five" -> 45
    "sixty" -> 60
    else -> word.toIntOrNull()
}

fun voiceAllowed(enabled: Boolean, kidsMode: Boolean, foreground: Boolean, permission: Boolean, available: Boolean) =
    enabled && adultRoutesAllowed(kidsMode) && foreground && permission && available

interface VoicePlayback {
    val ready: Boolean
    val chapterIndex: Int
    val chapterCount: Int
    val speed: Float
    fun play()
    fun pause()
    fun stop()
    fun chapter(index: Int)
    fun seekBy(ms: Long)
    fun speed(value: Float)
    fun timer(minutes: Int)
    fun cancelTimer()
}

class VoiceCommandExecutor(private val playback: VoicePlayback, private val page: (Boolean) -> Boolean = { false }) {
    fun execute(command: Command): String? {
        // Reader bookmarks/definition/read-from-here have no safe native JS entry point.
        if (command == Command.Bookmark) return "Reader bookmarks are not available by voice"
        if (command is Command.Page) return if (page(command.next)) null else "Open a reader first"
        if (!playback.ready) return "Choose an audiobook first"
        when (command) {
            Command.Play -> playback.play()
            Command.Pause -> playback.pause()
            Command.Stop -> playback.stop()
            is Command.Chapter -> {
                val index = playback.chapterIndex + command.delta
                if (index !in 0 until playback.chapterCount) return "No more chapters in that direction"
                playback.chapter(index)
            }
            is Command.Seek -> playback.seekBy(command.milliseconds)
            is Command.Speed -> playback.speed((if (command.relative) playback.speed + command.value else command.value).coerceIn(.75f, 2f))
            is Command.Timer -> playback.timer(command.minutes)
            Command.CancelTimer -> playback.cancelTimer()
            else -> Unit
        }
        return null
    }
}

interface VoiceRecognizer {
    fun start(result: (String?) -> Unit)
    fun cancel()
}

/** Main-thread owner. Session tokens invalidate duplicate and late provider callbacks. */
class VoiceCommandBus(
    private val recognizer: VoiceRecognizer,
    private val allowed: () -> Boolean,
    private val now: () -> Long,
    private val execute: (Command) -> String?,
    private val feedback: (String) -> Unit,
) {
    var listening = false
        private set
    private var generation = 0
    private var lastCommand: Long? = null
    fun start(): Boolean {
        if (!allowed() || listening || lastCommand?.let { now() - it < 1_500 } == true) return false
        listening = true
        val token = ++generation
        feedback("Listening · up to 6 seconds")
        recognizer.start result@{ text ->
            if (generation != token || !listening) return@result
            listening = false
            generation++
            recognizer.cancel()
            if (!allowed()) return@result
            if (text == null) { feedback("No speech recognized. Try again."); return@result }
            val command = VoiceCommandParser(text)
            if (command == null) { feedback("Heard: ${text.take(100)} · No matching command"); return@result }
            if (lastCommand?.let { now() - it < 1_500 } == true) return@result
            lastCommand = now()
            val problem = execute(command)
            feedback("Heard: ${text.take(100)}" + (problem?.let { " · $it" } ?: ""))
        }
        return true
    }
    fun cancel() {
        generation++
        listening = false
        recognizer.cancel()
    }
}
