package com.mavislibrary.app.data.podcast

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

const val PODCAST_MP3_MIME = "audio/mpeg"
const val PODCAST_DOWNLOADS_SUBFOLDER = "Mavis Podcasts"
const val SAVED_MP3_SNACKBAR = "Saved to Downloads/Mavis Podcasts"
const val MAX_MP3_FILENAME_CHARS = 120

private const val MAX_MP3_BYTES = 250L * 1024 * 1024
private const val SHARE_CACHE_DIR = "podcast_share"
private const val SHARE_CACHE_TTL_MS = 24L * 60 * 60 * 1000

private val SAFE_PUNCTUATION = setOf(
    '-', '_', '.', ',', '\'', '’', '(', ')', '[', ']', '&', '+', '!', ';', '—', '–', '·',
)

fun episodeExportTitle(job: PodcastJob): String = job.title.trim().ifEmpty { "Mavis Podcast" }

fun shareAuthority(context: Context): String = "${context.packageName}.share"

private fun isEmojiOrPictograph(cp: Int): Boolean {
    val type = Character.getType(cp)
    if (type == Character.OTHER_SYMBOL.toInt() ||
        type == Character.SURROGATE.toInt() ||
        type == Character.PRIVATE_USE.toInt()
    ) {
        return true
    }
    return cp in 0xFE00..0xFE0F ||
        cp in 0xE0100..0xE01EF ||
        cp == 0x200D ||
        cp == 0x20E3 ||
        cp in 0x1F000..0x1FAFF ||
        cp in 0x2600..0x27BF ||
        cp in 0x2300..0x23FF ||
        cp in 0x2B00..0x2BFF ||
        cp in 0xE0000..0xE007F
}

/**
 * Sanitises an episode title into a safe cross-platform filename base (without `.mp3`).
 * Removes path separators, FAT-forbidden characters, control characters, and emoji/pictographs.
 */
fun sanitizePodcastTitle(rawTitle: String): String {
    val normalized = Normalizer.normalize(rawTitle.trim(), Normalizer.Form.NFC)
    val withoutExt = if (normalized.endsWith(".mp3", ignoreCase = true)) {
        normalized.dropLast(4)
    } else {
        normalized
    }
    val builder = StringBuilder(withoutExt.length)
    val codePoints = withoutExt.codePoints().toArray()
    for (cp in codePoints) {
        val type = Character.getType(cp)
        when {
            isEmojiOrPictograph(cp) -> builder.append(' ')
            cp == '/'.code || cp == '\\'.code || cp == ':'.code -> builder.append('-')
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> builder.append(' ')
            Character.isLetter(cp) ||
                type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                type == Character.LETTER_NUMBER.toInt() -> builder.appendCodePoint(cp)
            type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() -> builder.appendCodePoint(cp)
            cp <= 0xFFFF && cp.toChar() in SAFE_PUNCTUATION -> builder.append(cp.toChar())
            else -> builder.append(' ')
        }
    }
    return builder.toString()
        .replace(Regex("\\.{2,}"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("-{2,}"), "-")
        .replace(Regex("(\\s*-\\s*){2,}"), " - ")
        .replace(Regex("\\s+"), " ")
        .trim { it == ' ' || it == '.' || it == '-' }
        .ifEmpty { "Mavis Podcast" }
}

private fun truncateSafeBase(cleanBase: String, maxChars: Int): String {
    if (cleanBase.length <= maxChars) {
        return cleanBase.trimEnd { it == ' ' || it == '.' || it == '-' }
    }
    val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundary.setText(cleanBase)
    var end = 0
    var next = boundary.first()
    while (next != BreakIterator.DONE) {
        if (next > maxChars) break
        end = next
        next = boundary.next()
    }
    return cleanBase.substring(0, end).trimEnd { it == ' ' || it == '.' || it == '-' }
}

/**
 * Safe MP3 filename capped at [maxChars] characters (default 120, including `.mp3` and any
 * duplicate suffix `" (2)"`, `" (3)"`, etc.), deduplicated case-insensitively against [existingNames].
 */
fun podcastMp3FileName(
    rawTitle: String,
    existingNames: Collection<String> = emptyList(),
    maxChars: Int = MAX_MP3_FILENAME_CHARS,
): String {
    require(maxChars >= 16) { "maxChars must leave room for duplicate suffix and .mp3." }
    val cleanBase = sanitizePodcastTitle(rawTitle)
    val taken = existingNames.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    var index = 1
    while (true) {
        val suffix = if (index == 1) ".mp3" else " ($index).mp3"
        val budget = (maxChars - suffix.length).coerceAtLeast(1)
        val base = truncateSafeBase(cleanBase, budget)
            .ifEmpty { truncateSafeBase("Mavis Podcast", budget).ifEmpty { "podcast".take(budget) } }
        val candidate = base + suffix
        if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        index++
    }
}

fun interface PodcastAudioSource {
    suspend fun writeTo(id: String, out: OutputStream, onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit)
}

fun PodcastApi.asAudioSource(): PodcastAudioSource = PodcastAudioSource { id, out, onProgress ->
    withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        audio(audioUrl(id)).use { response ->
            val body = response.body ?: throw IOException("Audio download was empty.")
            if (body.contentType()?.toString()?.substringBefore(';') !in setOf("audio/mpeg", "audio/mp3")) {
                throw IOException("Studio did not return MP3 audio.")
            }
            val expected = body.contentLength()
            onProgress(0L, expected)
            var total = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_MP3_BYTES) throw IOException("Podcast exceeds the 250 MB offline limit.")
                    out.write(buffer, 0, n)
                    onProgress(total, expected)
                }
            }
            if (total == 0L || (expected >= 0 && total != expected)) {
                throw IOException("Incomplete podcast download.")
            }
        }
    }
}

internal suspend fun copyLocalFileWithProgress(
    source: File,
    out: OutputStream,
    onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val coroutineContext = currentCoroutineContext()
    val expected = source.length()
    if (!source.isFile || expected <= 0L) throw IOException("Local podcast file is unavailable.")
    if (expected > MAX_MP3_BYTES) throw IOException("Podcast exceeds the 250 MB offline limit.")
    onProgress(0L, expected)
    var total = 0L
    source.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_MP3_BYTES) throw IOException("Podcast exceeds the 250 MB offline limit.")
            out.write(buffer, 0, n)
            onProgress(total, expected)
        }
    }
    if (total != expected) throw IOException("Incomplete local podcast copy.")
}

fun resolveLocalPodcastFile(
    saved: SavedPodcast,
    localLookup: (SavedPodcast) -> File? = { null },
): File? = saved.localFile?.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    ?: localLookup(saved)?.takeIf { it.isFile && it.length() > 0L }

data class SavedPodcastMp3(val uri: Uri, val fileName: String)

data class PreparedPodcastShare(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val sendIntent: Intent,
    val chooserIntent: Intent,
)

private suspend fun requireAdultExport(context: Context) {
    check(!context.kidsPrefs().state.first().enabled) { "Leave Kids mode to use Podcast Lounge." }
}

/**
 * Writes the raw studio MP3 (without re-encoding, preserving embedded ID3 metadata and intro/outro music)
 * to `Download/Mavis Podcasts/` via `MediaStore.Downloads` on API 29+.
 */
suspend fun savePodcastMp3(
    context: Context,
    saved: SavedPodcast,
    audioSource: PodcastAudioSource?,
    localLookup: (SavedPodcast) -> File? = { null },
    onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
): SavedPodcastMp3 {
    require(saved.job.status == "ready") { "This podcast isn't ready yet." }
    requireAdultExport(context)
    val local = resolveLocalPodcastFile(saved, localLookup)
    if (local == null && audioSource == null) {
        throw IOException("Choose this podcast's studio in Settings, or download it first.")
    }
    val title = episodeExportTitle(saved.job)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        savePodcastMp3MediaStore(context, saved.job.id, title, local, audioSource, onProgress)
    } else {
        savePodcastMp3Legacy(context, saved.job.id, title, local, audioSource, onProgress)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun savePodcastMp3MediaStore(
    context: Context,
    jobId: String,
    title: String,
    local: File?,
    audioSource: PodcastAudioSource?,
    onProgress: (Long, Long) -> Unit,
): SavedPodcastMp3 = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/$PODCAST_DOWNLOADS_SUBFOLDER/"
    val existing = queryExistingDownloadNames(resolver, relativeDir) +
        runCatching {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PODCAST_DOWNLOADS_SUBFOLDER)
                .list()?.toList().orEmpty()
        }.getOrDefault(emptyList())
    val fileName = podcastMp3FileName(title, existing)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, PODCAST_MP3_MIME)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Could not create MP3 in Downloads.")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open MP3 in Downloads.")
        stream.use { out ->
            if (local != null) {
                copyLocalFileWithProgress(local, out, onProgress)
            } else {
                audioSource!!.writeTo(StudioAddress.safeId(jobId), out, onProgress)
            }
            out.flush()
        }
        requireAdultExport(context)
        val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, publish, null, null)
        SavedPodcastMp3(uri, fileName)
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun queryExistingDownloadNames(resolver: ContentResolver, relativeDir: String): Set<String> {
    val names = HashSet<String>()
    val trimmed = relativeDir.trimEnd('/')
    runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativeDir, trimmed),
            null,
        )?.use { cursor ->
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (col >= 0 && cursor.moveToNext()) {
                cursor.getString(col)?.takeIf(String::isNotBlank)?.let(names::add)
            }
        }
    }
    return names
}

private suspend fun savePodcastMp3Legacy(
    context: Context,
    jobId: String,
    title: String,
    local: File?,
    audioSource: PodcastAudioSource?,
    onProgress: (Long, Long) -> Unit,
): SavedPodcastMp3 = withContext(Dispatchers.IO) {
    @Suppress("DEPRECATION")
    val publicDir = runCatching {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PODCAST_DOWNLOADS_SUBFOLDER)
            .takeIf { it.mkdirs() || it.isDirectory }
            ?.takeIf { it.canWrite() }
    }.getOrNull()
    val baseDir = publicDir
        ?: File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.cacheDir, SHARE_CACHE_DIR), PODCAST_DOWNLOADS_SUBFOLDER)
            .apply { mkdirs() }
    val fileName = podcastMp3FileName(title, baseDir.list()?.toList().orEmpty())
    val target = File(baseDir, fileName)
    val part = File(baseDir, "$fileName.part")
    try {
        part.outputStream().use { out ->
            if (local != null) copyLocalFileWithProgress(local, out, onProgress)
            else audioSource!!.writeTo(StudioAddress.safeId(jobId), out, onProgress)
            out.flush()
        }
        requireAdultExport(context)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not finish saving MP3.")
    } finally {
        part.delete()
    }
    runCatching { MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(PODCAST_MP3_MIME), null) }
    val shareCopy = writePodcastShareCacheCopy(context, jobId, fileName, target) { _, _ -> }
    val uri = FileProvider.getUriForFile(context, shareAuthority(context), shareCopy, fileName)
    SavedPodcastMp3(uri, fileName)
}

fun buildOpenSavedMp3Intent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, PODCAST_MP3_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

fun openSavedPodcastMp3(context: Context, uri: Uri) {
    try {
        context.startActivity(buildOpenSavedMp3Intent(uri))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

fun buildPodcastSendIntent(uri: Uri, rawTitle: String): Intent {
    val title = rawTitle.trim().ifEmpty { "Mavis Podcast" }
    return Intent(Intent.ACTION_SEND)
        .setType(PODCAST_MP3_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "$title — from Mavis Library")
        .apply {
            clipData = ClipData(ClipDescription(title, arrayOf(PODCAST_MP3_MIME)), ClipData.Item(uri))
        }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun buildPodcastShareChooser(sendIntent: Intent, rawTitle: String): Intent {
    val title = rawTitle.trim().ifEmpty { "Mavis Podcast" }
    return Intent.createChooser(sendIntent, title).apply {
        clipData = sendIntent.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun isUnderDirectory(file: File, dir: File): Boolean = runCatching {
    val rootPath = dir.canonicalPath.trimEnd(File.separatorChar) + File.separator
    file.canonicalPath.startsWith(rootPath)
}.getOrDefault(false)

private fun cleanupExpiredShareCache(shareRoot: File, nowMs: Long = System.currentTimeMillis()) {
    val entries = shareRoot.listFiles() ?: return
    for (entry in entries) {
        if (entry.isDirectory) {
            val files = entry.listFiles().orEmpty()
            files.filter { nowMs - it.lastModified() > SHARE_CACHE_TTL_MS }.forEach { it.delete() }
            if (entry.listFiles().isNullOrEmpty()) entry.delete()
        } else if (nowMs - entry.lastModified() > SHARE_CACHE_TTL_MS) {
            entry.delete()
        }
    }
}

private suspend fun writePodcastShareCacheCopy(
    context: Context,
    jobId: String,
    fileName: String,
    local: File?,
    audioSource: PodcastAudioSource? = null,
    onProgress: (Long, Long) -> Unit,
): File = withContext(Dispatchers.IO) {
    val shareRoot = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
    cleanupExpiredShareCache(shareRoot)
    val jobDir = File(shareRoot, StudioAddress.safeId(jobId)).apply { mkdirs() }
    val target = File(jobDir, fileName)
    if (target.isFile && target.length() > 0L && local == null) {
        target.setLastModified(System.currentTimeMillis())
        onProgress(target.length(), target.length())
        return@withContext target
    }
    val part = File(jobDir, "$fileName.part")
    try {
        part.outputStream().use { out ->
            if (local != null) {
                copyLocalFileWithProgress(local, out, onProgress)
            } else {
                audioSource!!.writeTo(StudioAddress.safeId(jobId), out, onProgress)
            }
            out.flush()
        }
        currentCoroutineContext().ensureActive()
        if (target.exists() && !target.delete()) throw IOException("Could not replace cached MP3.")
        if (!part.renameTo(target)) throw IOException("Could not prepare MP3 for sharing.")
        target
    } finally {
        part.delete()
    }
}

/**
 * Prepares a `content://` FileProvider MP3 URI (reusing an existing offline download directly when inside
 * `filesDir/audio/podcasts/`, or writing a raw cache copy under `cacheDir/podcast_share/` if not downloaded)
 * and builds the `ACTION_SEND` + chooser intents.
 */
suspend fun preparePodcastShare(
    context: Context,
    saved: SavedPodcast,
    audioSource: PodcastAudioSource?,
    localLookup: (SavedPodcast) -> File? = { null },
    onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
): PreparedPodcastShare {
    require(saved.job.status == "ready") { "This podcast isn't ready yet." }
    requireAdultExport(context)
    val title = episodeExportTitle(saved.job)
    val fileName = podcastMp3FileName(title)
    val local = resolveLocalPodcastFile(saved, localLookup)
    if (local == null && audioSource == null) {
        throw IOException("Choose this podcast's studio in Settings, or download it first.")
    }
    val downloadsRoot = File(context.filesDir, "audio/podcasts")
    val shareCacheRoot = File(context.cacheDir, SHARE_CACHE_DIR)
    val shareFile = if (local != null && (isUnderDirectory(local, downloadsRoot) || isUnderDirectory(local, shareCacheRoot))) {
        onProgress(local.length(), local.length())
        local
    } else {
        writePodcastShareCacheCopy(context, saved.job.id, fileName, local, audioSource, onProgress)
    }
    if (context.kidsPrefs().state.first().enabled) {
        if (isUnderDirectory(shareFile, shareCacheRoot)) shareFile.delete()
        check(false) { "Leave Kids mode to use Podcast Lounge." }
    }
    val uri = FileProvider.getUriForFile(context, shareAuthority(context), shareFile, fileName)
    val send = buildPodcastSendIntent(uri, title)
    val chooser = buildPodcastShareChooser(send, title)
    return PreparedPodcastShare(uri, fileName, title, send, chooser)
}

suspend fun sharePodcastMp3(
    context: Context,
    saved: SavedPodcast,
    audioSource: PodcastAudioSource?,
    localLookup: (SavedPodcast) -> File? = { null },
    onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
): PreparedPodcastShare {
    val prepared = preparePodcastShare(context, saved, audioSource, localLookup, onProgress)
    if (Looper.myLooper() == Looper.getMainLooper()) {
        context.startActivity(prepared.chooserIntent)
    } else {
        withContext(Dispatchers.Main) {
            context.startActivity(prepared.chooserIntent)
        }
    }
    return prepared
}

fun formatExportProgressLabel(baseLabel: String, bytesWritten: Long, totalBytes: Long): String {
    if (totalBytes > 0L) {
        val percent = ((bytesWritten * 100L) / totalBytes).coerceIn(0L, 100L)
        return "$baseLabel $percent%"
    }
    return baseLabel
}
