package com.mavislibrary.app.voice

import com.mavislibrary.app.audio.AudioCommands
import com.mavislibrary.app.audio.PlaybackController

class VoicePlaybackAdapter(private val controller: PlaybackController) : VoicePlayback {
    override val ready get() = controller.state.value.connected && chapterCount > 0
    override val chapterIndex get() = controller.state.value.chapterIndex
    override val chapterCount get() = controller.state.value.chapterCount
    override val speed get() = controller.state.value.speed
    override fun play() = controller.resume()
    override fun pause() = controller.pause()
    override fun stop() = controller.stop()
    override fun chapter(index: Int) = controller.chapter(index)
    override fun seekBy(ms: Long) = controller.seekBy(ms)
    override fun speed(value: Float) = controller.speed(value)
    override fun timer(minutes: Int) = controller.sleepMinutes(minutes)
    override fun cancelTimer() = controller.command(AudioCommands.SLEEP_CANCEL)
}
