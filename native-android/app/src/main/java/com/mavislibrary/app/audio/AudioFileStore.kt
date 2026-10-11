package com.mavislibrary.app.audio

import android.net.Uri
import com.mavislibrary.app.data.api.LibriVoxVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** App-private audiobook files. A completed file is preferred over its stream URL with no network check. */
class AudioFileStore(private val root: File, private val client: OkHttpClient) {
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
                val response = client.newCall(Request.Builder().url(section.url).build()).execute()
                response.use { res ->
                    if (!res.isSuccessful) throw IOException("Audio download failed (${res.code}).")
                    val body = res.body ?: throw IOException("Audio download was empty.")
                    part.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                }
                if (!part.renameTo(target)) throw IOException("Could not finish the audio download.")
            }
            progress(index + 1, version.sections.size)
        }
    }
}
