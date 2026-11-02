package com.mavislibrary.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

/** Published APKs only ever come from this repository's GitHub releases (a debug-only test build may point elsewhere). */
val RELEASE_DOWNLOADS: String = com.mavislibrary.app.BuildConfig.UPDATE_DOWNLOADS
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

/**
 * Downloads an update's APK into [dir], keeping it only if its SHA-256 matches what was published.
 *
 * Resumable: J's phone kept getting "connection reset" partway through the ~70-96 MB APK, and the old downloader
 * deleted the partial file and restarted from zero every time. Now the partial file (named by version AND hash, so
 * it can never be mixed with another build) is kept, the server is asked for only the missing bytes (HTTP Range),
 * and a dropped connection is retried in place a few times before giving up. A later attempt also resumes.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    private val attempts: Int = 5,
    private val retryDelayMs: Long = 2_000,
) {
    suspend fun download(info: UpdateInfo, progress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "mavis-${info.versionCode}.apk")
        val part = File(dir, "mavis-${info.versionCode}-${info.sha256.take(16)}.apk.part")
        // Leftovers from other versions/builds only; this build's partial file is what we resume from.
        dir.listFiles()?.filter { (it.name.endsWith(".apk") || it.name.endsWith(".part")) && it != part }?.forEach { it.delete() }
        var lastError: IOException? = null
        for (attempt in 1..attempts) {
            currentCoroutineContext().ensureActive()
            try {
                fetchInto(info, part, progress)
                val actual = sha256(part)
                if (actual != info.sha256) { part.delete(); throw IOException("The download was damaged. Try again.") }
                if (!part.renameTo(target)) throw IOException("Couldn't save the update.")
                return@withContext target
            } catch (e: IOException) {
                lastError = e
                if (e.message == "The download is too large." || e.message?.startsWith("The download failed (HTTP 4") == true) break
                if (attempt < attempts) delay(retryDelayMs * attempt)
            }
        }
        throw IOException(friendly(lastError), lastError)
    }

    /** Appends the missing bytes to [part]. Falls back to a fresh download if the server ignores the Range request. */
    private suspend fun fetchInto(info: UpdateInfo, part: File, progress: (Long, Long) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        if (have > MAX_APK_BYTES) { part.delete(); throw IOException("The download is too large.") }
        val request = Request.Builder().url(info.apkUrl).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            val resumed = have > 0 && response.code == 206
            if (have > 0 && response.code == 416) { part.delete(); throw IOException("The download restarted.") }
            if (!response.isSuccessful) throw IOException("The download failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("The download was empty.")
            val start = if (resumed) have else 0L
            val total = body.contentLength().let { if (it >= 0) start + it else -1L }
            if (total > MAX_APK_BYTES) throw IOException("The download is too large.")
            var done = start
            progress(done, total)
            java.io.FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("The download is too large.")
                        out.write(buffer, 0, n)
                        progress(done, total)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            if (total >= 0 && done < total) throw IOException("The connection dropped.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun friendly(e: IOException?): String = when {
        e?.message?.startsWith("The ") == true && e.message != "The connection dropped." && e.message != "The download restarted." -> e.message!!
        else -> "The connection keeps dropping. Your progress is saved, so tap Update again to pick up where it stopped."
    }
}
