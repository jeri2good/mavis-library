package com.mavislibrary.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the release workflow publishes as update.json next to each APK. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String = "",
    val publishedAt: String = "",
)

/** Published APKs only ever come from this repository's GitHub releases. */
const val RELEASE_DOWNLOADS = "https://github.com/jeri2good/mavis-library/releases/download/"
const val RELEASES_PAGE = "https://github.com/jeri2good/mavis-library/releases"
private const val MAX_MANIFEST_BYTES = 64 * 1024L
private const val MAX_APK_BYTES = 200L * 1024 * 1024

object UpdatePolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): UpdateInfo {
        val info = try { json.decodeFromString<UpdateInfo>(body) } catch (e: Exception) { throw IOException("The update information couldn't be read.", e) }
        val path = info.apkUrl.removePrefix(RELEASE_DOWNLOADS)
        if (info.versionCode <= 0 || info.versionName.isBlank() || path == info.apkUrl ||
            !path.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk")) || ".." in path ||
            !info.sha256.matches(Regex("[0-9a-f]{64}"))) throw IOException("The update information isn't valid.")
        return info.copy(versionName = info.versionName.take(40), notes = info.notes.take(500))
    }
}

/** Fetches update.json and returns it only when it is newer than [installedVersion]. */
class UpdateChecker(private val client: OkHttpClient, private val manifestUrl: String, private val installedVersion: Int) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()
        val body = client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // nothing published yet
            if (!response.isSuccessful) throw IOException("Couldn't check for updates (HTTP ${response.code}).")
            val source = response.body?.source() ?: throw IOException("Couldn't check for updates.")
            if (source.request(MAX_MANIFEST_BYTES + 1)) throw IOException("The update information is too large.")
            source.buffer.readUtf8()
        }
        UpdatePolicy.parse(body).takeIf { it.versionCode > installedVersion }
    }
}

/** Downloads an update's APK into [dir], keeping it only if its SHA-256 matches what was published. */
class UpdateDownloader(private val client: OkHttpClient, private val dir: File) {
    suspend fun download(info: UpdateInfo, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.endsWith(".apk") || it.name.endsWith(".part") }?.forEach { it.delete() }
        val target = File(dir, "mavis-${info.versionCode}.apk")
        val part = File(dir, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("The download was empty.")
                val total = body.contentLength()
                if (total > MAX_APK_BYTES) throw IOException("The download is too large.")
                var done = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > MAX_APK_BYTES) throw IOException("The download is too large.")
                            digest.update(buffer, 0, n); out.write(buffer, 0, n)
                            progress(done, total)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != info.sha256) throw IOException("The download was damaged. Try again.")
            if (!part.renameTo(target)) throw IOException("Couldn't save the update.")
            target
        } finally { part.delete() }
    }
}
