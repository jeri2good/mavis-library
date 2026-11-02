package com.mavislibrary.app.readaloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** The on-demand voice model. Smaller than kokoro-en-v0_19 (320 MB), and the only one of the two with af_heart. */
data class KokoroModel(
    val name: String = "kokoro-int8-multi-lang-v1_0",
    val url: String = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
    val sha256: String = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
    val archiveBytes: Long = 132_353_410L,
    val installedBytes: Long = 192_000_000L,
    val requiredFiles: List<String> = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "espeak-ng-data/phontab"),
)

/** A failure with a message written for the Settings screen. */
private class ModelError(message: String) : IOException(message)

sealed interface KokoroState {
    data class NotInstalled(val resumableBytes: Long = 0) : KokoroState
    data class Downloading(val done: Long, val total: Long) : KokoroState
    data object Verifying : KokoroState
    data object Unpacking : KokoroState
    data object Installed : KokoroState
    data class Failed(val message: String, val resumableBytes: Long = 0) : KokoroState
}

/**
 * Downloads, verifies and unpacks the model under files/tts. A partial download survives failures and restarts and
 * resumes with a Range request. Only a whole archive matching the pinned SHA-256 is ever unpacked or used.
 */
class KokoroModelStore(private val root: File, private val client: OkHttpClient, private val model: KokoroModel = KokoroModel()) {
    private val mutex = Mutex()
    private val archive get() = File(root, "${model.name}.tar.bz2.part")
    private val installDir get() = File(root, model.name)
    private val marker get() = File(root, "${model.name}.installed")

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<KokoroState> = _state.asStateFlow()

    private fun initialState(): KokoroState = if (isComplete()) KokoroState.Installed else KokoroState.NotInstalled(archive.length())

    private fun isComplete() = marker.isFile && model.requiredFiles.all { File(installDir, it).isFile }

    /** The unpacked model folder, or null while the model isn't fully installed. */
    fun modelDir(): File? = installDir.takeIf { isComplete() }

    suspend fun download(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (isComplete()) {
                _state.value = KokoroState.Installed
                return@withContext
            }
            try {
                root.mkdirs()
                val needed = model.archiveBytes - archive.length() + model.installedBytes
                if (root.usableSpace < needed) throw ModelError("Not enough free space. The voice needs about ${needed / 1_000_000} MB.")
                fetch()
                _state.value = KokoroState.Verifying
                if (sha256(archive) != model.sha256) {
                    archive.delete()
                    throw ModelError("The download was damaged. Try again.")
                }
                _state.value = KokoroState.Unpacking
                unpack()
                archive.delete()
                _state.value = KokoroState.Installed
            } catch (e: ModelError) {
                _state.value = KokoroState.Failed(e.message.orEmpty(), archive.length())
            } catch (_: IOException) {
                _state.value = KokoroState.Failed("The download stopped. Check your connection and try again.", archive.length())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = KokoroState.NotInstalled(archive.length())
                throw e
            }
        }
    }

    private suspend fun fetch() {
        var have = archive.length()
        if (have >= model.archiveBytes) {
            if (have == model.archiveBytes) return
            archive.delete()
            have = 0
        }
        val builder = Request.Builder().url(model.url)
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 416) {
                archive.delete()
                throw ModelError("The download couldn't resume. Try again.")
            }
            if (!response.isSuccessful) throw ModelError("The download failed (HTTP ${response.code}).")
            // A server may ignore Range and send the whole file, which then replaces the partial copy.
            val resuming = response.code == 206 && response.header("Content-Range").orEmpty().startsWith("bytes $have-")
            var done = if (resuming) have else 0L
            val body = response.body ?: throw ModelError("The download was empty.")
            _state.value = KokoroState.Downloading(done, model.archiveBytes)
            FileOutputStream(archive, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > model.archiveBytes) {
                            out.close()
                            archive.delete()
                            throw ModelError("The download was damaged. Try again.")
                        }
                        out.write(buffer, 0, n)
                        _state.value = KokoroState.Downloading(done, model.archiveBytes)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        }
    }

    private suspend fun unpack() {
        val temp = File(root, "${model.name}.unpacking")
        temp.deleteRecursively()
        temp.mkdirs()
        val base = temp.canonicalFile
        try {
            archive.inputStream().buffered().use { raw ->
                TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        currentCoroutineContext().ensureActive()
                        val target = File(base, entry.name).canonicalFile
                        if (!target.path.startsWith(base.path + File.separator) && target != base) throw ModelError("The voice package is not valid.")
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isFile -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { tar.copyTo(it) }
                            }
                        }
                    }
                }
            }
            val unpacked = File(temp, model.name)
            if (!model.requiredFiles.all { File(unpacked, it).isFile }) throw ModelError("The voice package is not valid.")
            marker.delete()
            installDir.deleteRecursively()
            if (!unpacked.renameTo(installDir)) throw ModelError("Couldn't save the voice.")
            marker.writeText(model.sha256)
        } finally {
            temp.deleteRecursively()
        }
    }

    suspend fun remove(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            marker.delete()
            installDir.deleteRecursively()
            archive.delete()
            _state.value = KokoroState.NotInstalled()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
