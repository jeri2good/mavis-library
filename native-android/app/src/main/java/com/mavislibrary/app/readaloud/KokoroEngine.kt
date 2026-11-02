package com.mavislibrary.app.readaloud

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Kokoro on sherpa-onnx. One thread synthesizes the next sentence while another plays the current one. The model holds
 * a few hundred MB of memory, so it loads at first use and is released after a few idle minutes.
 */
class KokoroEngine(private val modelDir: () -> File?, private val defaultVoice: () -> String) : SpeechEngine {
    private class Chunk(val samples: FloatArray?, val sampleRate: Int, val span: IntRange?, val error: String? = null)

    /**
     * sherpa-onnx's JNI looks up a non-static `invoke([F)Ljava/lang/Integer;` on the callback's own class. A Kotlin
     * lambda compiles to an invokedynamic synthetic class without that exact method, which aborted the app on device
     * (JNI NoSuchMethodError in kokoro-synth). A named class keeps the bridge method JNI expects.
     */
    private class KeepGoing(private val session: Session) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (session.cancelled) 0 else 1
    }

    private inner class Session(val request: SpeechRequest, val callback: SpeechCallback) {
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var track: AudioTrack? = null
        val chunks = ArrayBlockingQueue<Chunk>(READY_AHEAD)
    }

    private val synth = Executors.newSingleThreadScheduledExecutor { Thread(it, "kokoro-synth").apply { isDaemon = true } }
    private val lock = Any()
    private var session: Session? = null
    private var idleRelease: ScheduledFuture<*>? = null
    /** Touched on the synth thread only. */
    private var tts: OfflineTts? = null

    override fun speak(request: SpeechRequest, callback: SpeechCallback) {
        val next = Session(request, callback)
        synchronized(lock) {
            session?.let(::halt)
            session = next
            idleRelease?.cancel(false)
        }
        synth.execute { generate(next) }
        thread(name = "kokoro-play", isDaemon = true) { play(next) }
    }

    override fun stop() {
        synchronized(lock) {
            session?.let(::halt)
            session = null
            scheduleRelease()
        }
    }

    override fun pause(): Boolean {
        synchronized(lock) {
            session?.let { active ->
                active.paused = true
                runCatching { active.track?.pause() }
            }
        }
        return true
    }

    override fun resume() {
        synchronized(lock) {
            session?.let { active ->
                active.paused = false
                runCatching { active.track?.play() }
            }
        }
    }

    /** Frees the model now, for example before its files are deleted. */
    fun release() {
        stop()
        synth.execute(::freeModel)
    }

    private fun halt(active: Session) {
        active.cancelled = true
        runCatching {
            active.track?.pause()
            active.track?.flush()
        }
    }

    private fun scheduleRelease() {
        idleRelease?.cancel(false)
        idleRelease = synth.schedule(::freeModel, IDLE_RELEASE_MINUTES, TimeUnit.MINUTES)
    }

    private fun freeModel() {
        runCatching { tts?.release() }
        tts = null
    }

    private fun loadedModel(): OfflineTts? {
        tts?.let { return it }
        val dir = modelDir() ?: return null
        val path = dir.path
        return OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$path/model.int8.onnx",
                    voices = "$path/voices.bin",
                    tokens = "$path/tokens.txt",
                    dataDir = "$path/espeak-ng-data",
                    lexicon = "$path/lexicon-us-en.txt",
                    lang = "en-us",
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS),
            ),
            maxNumSentences = 1,
        )).also { tts = it }
    }

    private fun generate(active: Session) {
        val outcome = runCatching {
            if (active.cancelled) return
            // UnsatisfiedLinkError and other linkage failures count too: the dispatcher falls back to the phone voice.
            val model = loadedModel() ?: throw IllegalStateException("model missing")
            val wanted = active.request.voiceUri?.removePrefix(NATURAL_PREFIX) ?: defaultVoice()
            val voice = NATURAL_VOICES.find { it.id == wanted } ?: NATURAL_VOICES.first()
            val speed = active.request.rate.coerceIn(MIN_RATE, MAX_RATE)
            for (span in splitSentences(active.request.text)) {
                if (active.cancelled) return
                val audio = model.generateWithCallback(active.request.text.substring(span), voice.speakerId, speed, KeepGoing(active))
                if (active.cancelled) return
                if (!put(active, Chunk(audio.samples, audio.sampleRate, span))) return
            }
        }
        if (outcome.isFailure) {
            freeModel()
            put(active, Chunk(null, 0, null, "synthesis-unavailable"))
        } else {
            put(active, Chunk(null, 0, null))
        }
    }

    private fun put(active: Session, chunk: Chunk): Boolean {
        while (!active.cancelled) {
            if (active.chunks.offer(chunk, 100, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun play(active: Session) {
        var written = 0L
        var finished = false
        val callback = active.callback
        var started = false
        try {
            while (!active.cancelled) {
                val chunk = active.chunks.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (chunk.error != null) {
                    callback.onError(chunk.error)
                    return
                }
                val samples = chunk.samples ?: break
                if (samples.isEmpty()) continue
                val track = active.track ?: newTrack(active, chunk.sampleRate)
                if (!started) {
                    started = true
                    callback.onStart()
                }
                chunk.span?.let { callback.onBoundary("sentence", it.first, it.last - it.first + 1) }
                var offset = 0
                while (offset < samples.size && !active.cancelled) {
                    val n = track.write(samples, offset, minOf(WRITE_SAMPLES, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (n < 0) {
                        callback.onError("audio-hardware")
                        return
                    }
                    if (n == 0) Thread.sleep(10)
                    offset += n
                    written += n
                }
            }
            if (active.cancelled) return
            if (!started) callback.onStart()
            active.track?.let { track ->
                // `end` should fire when the voice actually stops, but after stop() the head position reads 0. Silence
                // fills the buffer instead, so even a short sentence reaches the track's start threshold.
                track.write(FloatArray(track.bufferSizeInFrames), 0, track.bufferSizeInFrames, AudioTrack.WRITE_NON_BLOCKING)
                var waited = 0L
                while (!active.cancelled && track.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written && waited < DRAIN_TIMEOUT_MS) {
                    Thread.sleep(20)
                    if (!active.paused) waited += 20
                }
            }
            if (!active.cancelled) finished = true
        } catch (_: RuntimeException) {
            // AudioTrack reports a busy or unsupported output by throwing.
            if (!active.cancelled) callback.onError("audio-hardware")
        } finally {
            val track = active.track
            active.track = null
            runCatching { track?.release() }
            synchronized(lock) {
                if (session === active) {
                    session = null
                    scheduleRelease()
                }
            }
        }
        if (finished) callback.onDone()
    }

    private fun newTrack(active: Session, sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(active.request.volume.coerceIn(0f, 1f))
        synchronized(lock) {
            active.track = track
            if (!active.paused && !active.cancelled) track.play()
        }
        return track
    }

    companion object {
        private const val READY_AHEAD = 2
        private const val WRITE_SAMPLES = 2_048
        private const val MAX_THREADS = 4
        private const val MIN_RATE = .5f
        private const val MAX_RATE = 2f
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val IDLE_RELEASE_MINUTES = 3L

        /** sherpa-onnx is packaged for these ABIs only (see packaging in build.gradle.kts). */
        fun supported(abis: Array<String> = Build.SUPPORTED_ABIS) = abis.any { it == "arm64-v8a" || it == "x86_64" }
    }
}
