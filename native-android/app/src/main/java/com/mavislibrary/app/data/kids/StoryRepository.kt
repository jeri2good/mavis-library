package com.mavislibrary.app.data.kids

import android.content.Context
import com.mavislibrary.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class StoryRepository(
    context: Context,
    private val base: String = BuildConfig.KIDS_PACK_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(),
) {
    val root = File(context.filesDir, "kids").apply { mkdirs() }
    private val mutex = Mutex()
    private val cache = File(root, "catalog.json")
    fun cachedCatalog(): StoryCatalog? = runCatching { StoryPacks.catalog(cache.readText()) }.getOrNull()

    suspend fun catalog(): StoryCatalog = withContext(Dispatchers.IO) {
        try {
            val text = fetch("catalog.json", 2 * 1024 * 1024).toString(Charsets.UTF_8)
            val parsed = StoryPacks.catalog(text)
            atomicWrite(cache, text.toByteArray())
            parsed
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            cachedCatalog() ?: throw e
        }
    }

    suspend fun cover(entry: StoryEntry): File? = withContext(Dispatchers.IO) {
        val target = File(root, "cover-${entry.id}.jpg")
        if (target.isFile) return@withContext target
        try { atomicWrite(target, fetch(entry.cover, 4 * 1024 * 1024)); target }
        catch (_: IOException) { null }
    }

    suspend fun preview(voice: String, catalog: StoryCatalog?): File? = withContext(Dispatchers.IO) {
        require(voice in STORY_VOICES)
        // v1 does not mandate a preview field. Accept it when supplied, otherwise try this convention.
        val path = catalog?.voices?.find { it.id == voice }?.preview ?: "preview-$voice.mp3"
        val target = File(root, "preview-$voice.mp3")
        if (target.isFile) return@withContext target
        try { atomicWrite(target, fetch(path, 2 * 1024 * 1024)); target }
        catch (_: IOException) { null }
    }

    fun directory(entry: StoryEntry): File = StoryPacks.file(root, entry.id)
    fun installed(entry: StoryEntry): Boolean = runCatching {
        val dir = directory(entry)
        File(dir, ".sha256").readText() == entry.sha256 && load(entry).id == entry.id
    }.getOrDefault(false)
    fun load(entry: StoryEntry): Story = StoryPacks.story(File(directory(entry), "story.json").readText()).also {
        require(it.id == entry.id)
    }

    suspend fun download(entry: StoryEntry, progress: (Float) -> Unit): Story = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (installed(entry)) return@withContext load(entry)
            val part = File(root, "${entry.id}-${entry.sha256}.part")
            if (part.length() > entry.bytes) part.delete()
            var attempt = 0
            while (part.length() != entry.bytes) {
                currentCoroutineContext().ensureActive()
                try {
                    val offset = part.length()
                    progress(offset.toFloat() / entry.bytes)
                    val request = Request.Builder().url(url(entry.zip)).apply {
                        if (offset > 0) header("Range", "bytes=$offset-")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 416) { part.delete(); throw IOException("Please retry the download.") }
                        if (!response.isSuccessful) throw IOException("Download unavailable (${response.code}).")
                        val append = offset > 0 && response.code == 206
                        if (response.code == 206) {
                            require(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid download range" }
                        }
                        val body = response.body ?: throw IOException("Empty download")
                        FileOutputStream(part, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                var total = if (append) offset else 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= entry.bytes) { "Download exceeds catalog size" }
                                    output.write(buffer, 0, n)
                                    progress(total.toFloat() / entry.bytes)
                                }
                            }
                        }
                        if (part.length() != entry.bytes) throw IOException("Download interrupted. Tap to resume.")
                    }
                } catch (e: IOException) {
                    if (++attempt >= 3) throw e
                }
            }
            try { verify(part, entry.sha256) }
            catch (e: IllegalArgumentException) { part.delete(); throw e }
            val staging = File(root, ".${entry.id}-unpacking")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                extract(part, staging)
                val story = StoryPacks.story(File(staging, "story.json").readText())
                require(story.id == entry.id)
                validateFiles(staging, story)
                File(staging, ".sha256").writeText(entry.sha256)
                val destination = directory(entry)
                destination.deleteRecursively()
                check(staging.renameTo(destination)) { "Couldn't install story" }
                part.delete()
                story
            } finally { staging.deleteRecursively() }
        }
    }

    private fun url(path: String) = base.trimEnd('/') + "/" + StoryPacks.relative(path)
    private fun fetch(path: String, limit: Int): ByteArray {
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Content unavailable (${response.code})")
            val input = response.body?.byteStream() ?: throw IOException("Empty response")
            return input.use {
                val bytes = it.readBytesLimited(limit)
                bytes
            }
        }
    }

    companion object {
        fun verify(file: File, expected: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expected, ignoreCase = true)) { "Story checksum mismatch. Tap to download again." }
        }
        fun extract(zip: File, directory: File) {
            var size = 0L
            var count = 0
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(++count <= 10_000) { "Too many story files" }
                    val file = StoryPacks.file(directory, entry.name.trimEnd('/'))
                    if (entry.isDirectory) file.mkdirs()
                    else {
                        require(!file.exists()) { "Duplicate story file" }
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                size += n
                                require(size <= StoryPacks.MAX_ZIP_BYTES * 2) { "Expanded story too large" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    input.closeEntry()
                }
            }
        }
        fun validateFiles(directory: File, story: Story) {
            val paths = listOf(story.music) + story.pages.flatMap { page ->
                listOf(page.img) + page.audio.values.flatMap { it.values }
            }
            paths.forEach { require(StoryPacks.file(directory, it).isFile) { "Story file missing: $it" } }
        }
        private fun atomicWrite(file: File, bytes: ByteArray) {
            val temp = File(file.path + ".tmp")
            temp.writeBytes(bytes)
            check(temp.renameTo(file))
        }
        private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) return output.toByteArray()
                require(output.size() + n <= limit) { "Content too large" }
                output.write(buffer, 0, n)
            }
        }
    }
}
