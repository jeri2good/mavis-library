package com.mavislibrary.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class VoiceCommandParserTest(private val phrase: String, private val expected: Command?) {
    @Test fun parse() { assertEquals(phrase, expected, VoiceCommandParser(phrase)) }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any?>> = listOf(
            "play" to Command.Play, "resume" to Command.Play, "pause" to Command.Pause,
            "stop" to Command.Stop, "next chapter" to Command.Chapter(1), "previous chapter" to Command.Chapter(-1),
            "back 30 seconds" to Command.Seek(-30_000), "forward a minute" to Command.Seek(60_000),
            "skip 15 seconds" to Command.Seek(15_000), "back thirty seconds" to Command.Seek(-30_000),
            "forward one minute" to Command.Seek(60_000), "sleep timer 20 minutes" to Command.Timer(20),
            "sleep timer twenty minutes" to Command.Timer(20), "sleep timer 1 minute" to Command.Timer(1),
            "sleep timer 120 minutes" to Command.Timer(120), "cancel timer" to Command.CancelTimer,
            "cancel sleep timer" to Command.CancelTimer, "speed up" to Command.Speed(.25f, true),
            "slower" to Command.Speed(-.25f, true), "1.5x" to Command.Speed(1.5f),
            "speed 0.75x" to Command.Speed(.75f), "bookmark this" to Command.Bookmark,
            "next page" to Command.Page(true), "previous page" to Command.Page(false),
            " HEY MAVIS please PAUSE! " to Command.Pause, "please play" to Command.Play,
            "resume please" to Command.Play, "hey mavis next chapter please" to Command.Chapter(1),
            "stop playback" to Command.Stop, "slow down" to Command.Speed(-.25f, true),
            "2x" to Command.Speed(2f), "play audio" to Command.Play,
            "" to null, "what time is it" to null, "play something interesting" to null,
            "don't play" to null, "pause and next chapter" to null, "the narrator said pause" to null,
            "please please play" to null, "sleep timer 0 minutes" to null, "sleep timer 121 minutes" to null,
            "speed 9x" to null, "skip 999 seconds" to null, "forward sixty minutes" to null,
            "define word" to null, "read from here" to null, "bookmark" to null,
            "hey mavis please play please please please please please" to null,
            "play\nstop" to null, "play;pause" to null, "x".repeat(161) to null,
        ).map { arrayOf(it.first, it.second) }
    }
}
