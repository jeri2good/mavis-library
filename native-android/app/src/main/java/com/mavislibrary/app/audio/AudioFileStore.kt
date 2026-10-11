package com.mavislibrary.app.audio

import android.net.Uri
import com.mavislibrary.app.data.api.LibriVoxVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** App-private audiobook files. A completed file is preferred over its stream URL with no network check. */
class AudioFileStore(
    private val root: File,
    private val client: OkHttpClient,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private class HttpFailure(val status: Int) : IOException("HTTP $status")
    private val retryDelays = longArrayOf(1_000, 3_000, 8_000)
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
    private fun file(versionId: String, chapter: Int) = root.resolve("audio/${safe(versionId)}/$chapter.mp3")

    fun playableUri(versionId: String, chapter: Int, streamUrl: String): Uri {
        val local = file(versionId, chapter)
        return if (local.isFile && local.length() > 0) Uri.fromFile(local) else Uri.parse(streamUrl.replaceFirst("://www.archive.org/", "://archive.org/"))
    }

    fun isComplete(version: LibriVoxVersion): Boolean =
        version.sections.isNotEmpty() && version.sections.indices.all { file(version.id, it).isFile }

    suspend fun download(version: LibriVoxVersion, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        version.sections.forEachIndexed { index, section ->
            val target = file(version.id, index)
            if (!target.isFile || target.length() == 0L) {
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, "${target.name}.part")
                for (attempt in 0..retryDelays.size) {
                    try {
                        if (part.exists() && !part.delete()) throw IOException("Could not remove partial download.")
                        client.newCall(Request.Builder().url(section.url).build()).execute().use { res ->
                            if (!res.isSuccessful) throw HttpFailure(res.code)
                            val body = res.body ?: throw IOException("Audio download was empty.")
                            part.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                        }
                        if (!part.renameTo(target)) throw IOException("Could not finish the audio download.")
                        break
                    } catch (failure: IOException) {
                        val retryable = failure !is HttpFailure || failure.status == 429 || failure.status in 500..599
                        if (!retryable || attempt == retryDelays.size) {
                            val reason = if (failure is HttpFailure) "HTTP ${failure.status}" else "I/O error"
                            throw IOException(
                                "Chapter ${index + 1} of ${version.sections.size} failed ($reason). " +
                                    "Tap Save offline to continue - the chapters already saved are kept.",
                                failure,
                            )
                        }
                        sleeper(retryDelays[attempt])
                    }
                }
            }
            progress(index + 1, version.sections.size)
        }
    }
}
