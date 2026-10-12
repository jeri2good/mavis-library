package com.mavislibrary.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.mavislibrary.app.data.podcast.PodcastApi
import com.mavislibrary.app.data.podcast.PodcastSettings
import com.mavislibrary.app.data.podcast.StudioAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

@androidx.annotation.OptIn(UnstableApi::class)
class PodcastDataSource(private val api: PodcastApi) : BaseDataSource(true) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var left = C.LENGTH_UNSET.toLong()
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val range = if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            "bytes=${dataSpec.position}-" + if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
        } else null
        val r = runBlocking { api.audio(dataSpec.uri.toString(), range) }
        response = r
        try {
            if (r.body?.contentType()?.toString()?.substringBefore(';') !in setOf("audio/mpeg", "audio/mp3")) throw IOException("Studio did not return MP3 audio.")
            val stream = r.body!!.byteStream()
            input = stream
            if (r.code == 206 && r.header("Content-Range")?.startsWith("bytes ${dataSpec.position}-") != true) throw IOException("Invalid audio range.")
            if (r.code == 200 && dataSpec.position > 0) {
                var skip = dataSpec.position
                while (skip > 0) {
                    val n = stream.skip(skip)
                    if (n > 0) skip -= n else if (stream.read() < 0) throw IOException("Audio position is beyond the file.") else skip--
                }
            }
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.body!!.contentLength().let {
                if (it < 0) C.LENGTH_UNSET.toLong() else (it - if (r.code == 200) dataSpec.position else 0).coerceAtLeast(0)
            }
            uri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return left
        } catch (e: Exception) { close(); throw e }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = input?.read(buffer, offset, if (left < 0) length else minOf(length.toLong(), left).toInt()) ?: C.RESULT_END_OF_INPUT
        if (n > 0) { if (left > 0) left -= n; bytesTransferred(n) }
        return n
    }
    override fun getUri() = uri
    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
    override fun close() {
        response?.close(); response = null; input = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PodcastDataSourceFactory(context: Context, private val api: PodcastApi) : DataSource.Factory {
    private val fallback = DefaultDataSource.Factory(context)
    override fun createDataSource(): DataSource = object : DataSource {
        private var delegate: DataSource? = null
        private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
        override fun open(dataSpec: DataSpec): Long {
            delegate = if (api.isAudioUrl(dataSpec.uri.toString())) PodcastDataSource(api) else fallback.createDataSource()
            listeners.forEach { delegate!!.addTransferListener(it) }
            return delegate!!.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
        override fun getUri() = delegate?.uri
        override fun getResponseHeaders() = delegate?.responseHeaders.orEmpty()
        override fun close() { delegate?.close(); delegate = null }
    }
}
