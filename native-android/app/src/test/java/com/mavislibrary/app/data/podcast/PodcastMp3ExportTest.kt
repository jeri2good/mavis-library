package com.mavislibrary.app.data.podcast

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

class FakeDownloadsMediaProvider : ContentProvider() {
    data class Entry(
        val id: Long,
        val values: ContentValues,
        val file: File,
    )

    private var nextId = 1L
    val entries = linkedMapOf<Long, Entry>()

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = nextId++
        val dir = File(context!!.cacheDir, "fake_mediastore").apply { mkdirs() }
        val backing = File(dir, "item_$id.bin").apply { writeBytes(ByteArray(0)) }
        entries[id] = Entry(id, ContentValues(values ?: ContentValues()), backing)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = entries[ContentUris.parseId(uri)] ?: throw java.io.FileNotFoundException("No entry for $uri")
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val entry = entries[ContentUris.parseId(uri)] ?: return 0
        values?.let(entry.values::putAll)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val removed = entries.remove(ContentUris.parseId(uri)) ?: return 0
        removed.file.delete()
        return 1
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = MatrixCursor(cols)
        val allowedPaths = selectionArgs?.toSet()
        for (entry in entries.values) {
            val rel = entry.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
            if (allowedPaths != null && rel !in allowedPaths) continue
            cursor.addRow(cols.map { col -> entry.values.get(col) })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        entries[ContentUris.parseId(uri)]?.values?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
}

/**
 * Robolectric creates a fresh dataDir/cacheDir per test method, whereas AndroidX FileProvider caches
 * parsed root paths statically by authority until `attachInfo` runs. Re-attaching provider metadata
 * before and after each test clears that static entry via public ContentProvider lifecycle API.
 */
fun resetShareFileProvider(context: Context) {
    val authority = shareAuthority(context)
    val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
    if (info != null) {
        FileProvider().attachInfo(context, info)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastMp3ExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var mediaProvider: FakeDownloadsMediaProvider

    @Before
    fun setUp() {
        resetShareFileProvider(context)
        mediaProvider = Robolectric.setupContentProvider(FakeDownloadsMediaProvider::class.java, MediaStore.AUTHORITY)
        File(context.cacheDir, "podcast_share").deleteRecursively()
        File(context.filesDir, "audio/podcasts").deleteRecursively()
    }

    @After
    fun tearDown() {
        resetShareFileProvider(context)
    }

    /** Realistic raw studio MP3 fixture: ID3v2 header with title + "Mavis Podcast Lounge" artist, intro, talk, outro. */
    private fun rawStudioMp3Fixture(title: String = "Psalm 23 - Bible lesson"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3\u0003\u0000\u0000\u0000\u0000\u0001\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write("TIT2:$title|TPE1:Mavis Podcast Lounge|".toByteArray(Charsets.UTF_8))
        out.write("INTRO_MUSIC_FRAMES".toByteArray(Charsets.US_ASCII))
        // 20 KB payload so multi-chunk streaming and progress reporting are exercised.
        val frame = ByteArray(256) { i -> ((i * 31 + 0xFB) and 0xFF).toByte() }
        repeat(80) { out.write(frame) }
        out.write("OUTRO_MUSIC_FRAMES".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun readyPodcast(
        id: String = "job1",
        title: String = "Psalm 23 - Bible lesson",
        studio: String = "https://site.example.org",
        localFile: String? = null,
    ) = SavedPodcast(
        job = PodcastJob(id = id, status = "ready", title = title, kind = "chapter", style = "deep-dive", durationSec = 600),
        studio = studio,
        localFile = localFile,
        accountId = "user-1",
    )

    private fun binaryAudioResponse(request: Request, bytes: ByteArray, contentType: String = "audio/mpeg"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(contentType.toMediaType()))
            .build()

    @Test
    fun filenameSanitisingHandlesSlashesIllegalCharsAndPathTraversal() {
        assertEquals("Psalm 23 - Bible lesson.mp3", podcastMp3FileName("Psalm 23 - Bible lesson"))
        assertEquals("Psalm 23-24 - Shepherd.mp3", podcastMp3FileName("Psalm 23/24 - Shepherd"))
        assertEquals("Website - Article - Update.mp3", podcastMp3FileName("Website / Article \\ Update"))
        assertEquals("etc-passwd.mp3", podcastMp3FileName("../../etc/passwd"))
        assertEquals("John 3-16 What Now Love.mp3", podcastMp3FileName("John 3:16 * What? <Now> | \"Love\"\n\t"))
        assertEquals("Psalm 23.mp3", podcastMp3FileName("Psalm 23.MP3"))
        assertFalse(podcastMp3FileName("../../a/b\\c").contains('/'))
        assertFalse(podcastMp3FileName("../../a/b\\c").contains('\\'))
        assertFalse(podcastMp3FileName("../../a/b\\c").contains(".."))
    }

    @Test
    fun filenameSanitisingStripsEmojiAndSurrogatesWithSafeFallback() {
        assertEquals("Psalm 23 - Bible lesson.mp3", podcastMp3FileName("🎧 Psalm 23 - Bible lesson 🔥"))
        assertEquals("Family & Faith 1.mp3", podcastMp3FileName("👨‍👩‍👧‍👦 Family & Faith 🙏🏽 🇺🇸 1️⃣"))
        assertEquals("broken surrogate.mp3", podcastMp3FileName("\uD83Dbroken surrogate"))
        assertEquals("Mavis Podcast.mp3", podcastMp3FileName("🎧🔥✨"))
        assertEquals("Mavis Podcast.mp3", podcastMp3FileName("   ///   "))
    }

    @Test
    fun filenameEnforces120CharCapIncludingExtensionAndDuplicates() {
        val longAscii = podcastMp3FileName("A".repeat(200))
        assertEquals(120, longAscii.length)
        assertEquals("A".repeat(116) + ".mp3", longAscii)

        val longDuplicate = podcastMp3FileName("A".repeat(200), listOf(longAscii))
        assertEquals(120, longDuplicate.length)
        assertEquals("A".repeat(112) + " (2).mp3", longDuplicate)

        val longUnicode = podcastMp3FileName("Café ".repeat(50))
        assertTrue(longUnicode.length <= 120)
        assertTrue(longUnicode.endsWith(".mp3"))
        assertFalse(longUnicode.endsWith(" .mp3"))

        val withBoundaryEmoji = podcastMp3FileName("B".repeat(115) + "🎧C".repeat(20))
        assertTrue(withBoundaryEmoji.length <= 120)
        assertTrue(withBoundaryEmoji.none(Char::isSurrogate))
    }

    @Test
    fun duplicateNamesAppendTwoThenThreeCaseInsensitively() {
        val first = podcastMp3FileName("Psalm 23 - Bible lesson")
        assertEquals("Psalm 23 - Bible lesson.mp3", first)

        val second = podcastMp3FileName("Psalm 23 - Bible lesson", listOf(first))
        assertEquals("Psalm 23 - Bible lesson (2).mp3", second)

        val third = podcastMp3FileName("Psalm 23 - Bible lesson", listOf(first, second))
        assertEquals("Psalm 23 - Bible lesson (3).mp3", third)

        val caseCollision = podcastMp3FileName("Psalm 23 - Bible lesson", listOf("psalm 23 - bible lesson.mp3"))
        assertEquals("Psalm 23 - Bible lesson (2).mp3", caseCollision)
    }

    @Test
    fun saveMp3CopiesDownloadedLocalFilePreservingRawBytesAndDeduplicates() = runBlocking {
        val rawMp3 = rawStudioMp3Fixture()
        val localFile = File(context.filesDir, "audio/podcasts/origin/job1.mp3").apply {
            parentFile!!.mkdirs()
            writeBytes(rawMp3)
        }
        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        val failIfNetworkCalled = PodcastAudioSource { _, _, _ -> error("Network must not run when localFile exists") }

        val first = savePodcastMp3(
            context = context,
            saved = readyPodcast(localFile = localFile.absolutePath),
            audioSource = failIfNetworkCalled,
            onProgress = { written, total -> progressUpdates += written to total },
        )
        assertEquals("Psalm 23 - Bible lesson.mp3", first.fileName)
        assertTrue(progressUpdates.first().first == 0L)
        assertEquals(rawMp3.size.toLong() to rawMp3.size.toLong(), progressUpdates.last())

        val entry1 = mediaProvider.entries.values.first()
        assertEquals("Psalm 23 - Bible lesson.mp3", entry1.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("audio/mpeg", entry1.values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/Mavis Podcasts/", entry1.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(0, entry1.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals("Raw MP3 (ID3 + intro/outro) must not be re-encoded", rawMp3, entry1.file.readBytes())

        val second = savePodcastMp3(
            context = context,
            saved = readyPodcast(id = "job2", localFile = localFile.absolutePath),
            audioSource = failIfNetworkCalled,
        )
        assertEquals("Psalm 23 - Bible lesson (2).mp3", second.fileName)

        val openIntent = buildOpenSavedMp3Intent(first.uri)
        assertEquals(Intent.ACTION_VIEW, openIntent.action)
        assertEquals(first.uri, openIntent.data)
        assertEquals("audio/mpeg", openIntent.type)
        assertNotEquals(0, openIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun saveMp3StreamsSignedUrlWithoutForwardingCredentialsAndDeletesPendingOnFailure() = runBlocking {
        val rawMp3 = rawStudioMp3Fixture("John 3 / Born Again 🎧")
        val seenRequests = mutableListOf<Request>()
        val api = PodcastApi(
            address = "https://site.example.org",
            execute = { request ->
                seenRequests += request
                fakeSignedAudio(request) ?: binaryAudioResponse(request, rawMp3)
            },
            owner = { "fixture-owner" },
            token = { "fixture-token" },
        )
        val progress = mutableListOf<Pair<Long, Long>>()
        val savedMp3 = savePodcastMp3(
            context = context,
            saved = readyPodcast(id = "job_stream", title = "John 3 / Born Again 🎧", localFile = null),
            audioSource = api.asAudioSource(),
            onProgress = { written, total -> progress += written to total },
        )
        assertEquals("John 3 - Born Again.mp3", savedMp3.fileName)
        assertEquals(2, seenRequests.size)
        assertEquals("Bearer fixture-token", seenRequests[0].header("Authorization"))
        assertNull("Studio stream must not receive Authorization", seenRequests[1].header("Authorization"))
        assertNull("Studio stream must not receive owner code", seenRequests[1].header("x-mavis-access"))
        assertArrayEquals(rawMp3, mediaProvider.entries.values.single().file.readBytes())
        assertTrue(progress.size >= 2)
        assertEquals(rawMp3.size.toLong(), progress.last().first)

        // Broken stream must remove pending MediaStore entry instead of leaving a half-written file.
        val countBefore = mediaProvider.entries.size
        val truncatedBody = object : okhttp3.ResponseBody() {
            private val buffer = okio.Buffer().write(ByteArray(128))
            override fun contentType() = "audio/mpeg".toMediaType()
            override fun contentLength() = 99_999L
            override fun source() = buffer
        }
        val truncatedApi = PodcastApi(
            address = "https://site.example.org",
            execute = { request ->
                fakeSignedAudio(request) ?: Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(Headers.headersOf("Content-Length", "99999"))
                    .body(truncatedBody)
                    .build()
            },
            token = { "fixture-token" },
        )
        assertTrue(
            runCatching {
                savePodcastMp3(context, readyPodcast(id = "job_bad", localFile = null), truncatedApi.asAudioSource())
            }.isFailure,
        )
        assertEquals(countBefore, mediaProvider.entries.size)
    }

    @Test
    fun sharePreparesFileProviderUriMimeSubjectTextClipDataAndUriGrants() = runBlocking {
        val rawMp3 = rawStudioMp3Fixture("Psalm 23 - Bible lesson")
        val downloaded = File(context.filesDir, "audio/podcasts/sha256/job_dl.mp3").apply {
            parentFile!!.mkdirs()
            writeBytes(rawMp3)
        }
        // 1. Downloaded episode shares directly via FileProvider without duplicating into cacheDir.
        val directShare = preparePodcastShare(
            context = context,
            saved = readyPodcast(id = "job_dl", title = "Psalm 23 - Bible lesson", localFile = downloaded.absolutePath),
            audioSource = PodcastAudioSource { _, _, _ -> error("Should not stream when already downloaded") },
        )
        assertFalse(File(context.cacheDir, "podcast_share/job_dl").exists())
        assertShareContract(directShare, "Psalm 23 - Bible lesson", "Psalm 23 - Bible lesson.mp3", rawMp3)

        // 2. Non-downloaded episode writes cache copy and exposes it via FileProvider.
        val api = PodcastApi(
            address = "https://site.example.org",
            execute = { request -> fakeSignedAudio(request) ?: binaryAudioResponse(request, rawMp3) },
            token = { "fixture-token" },
        )
        val cachedShare = preparePodcastShare(
            context = context,
            saved = readyPodcast(id = "job_remote", title = "Psalm 23 - Bible lesson", localFile = null),
            audioSource = api.asAudioSource(),
        )
        assertTrue(File(context.cacheDir, "podcast_share/job_remote/Psalm 23 - Bible lesson.mp3").isFile)
        assertShareContract(cachedShare, "Psalm 23 - Bible lesson", "Psalm 23 - Bible lesson.mp3", rawMp3)

        // 3. Private app files outside share/, podcast_share/, and audio/podcasts/ remain blocked.
        val privateImport = File(context.filesDir, "imports/secret.epub").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2)) }
        assertTrue(runCatching { FileProvider.getUriForFile(context, shareAuthority(context), privateImport) }.isFailure)
    }

    @Test
    fun kidsModeAndUnfinishedEpisodesAreRejected() = runBlocking {
        val rawMp3 = rawStudioMp3Fixture()
        val local = File(context.filesDir, "audio/podcasts/sha/job1.mp3").apply { parentFile!!.mkdirs(); writeBytes(rawMp3) }
        val unfinished = SavedPodcast(
            PodcastJob("job_writing", "writing", title = "Draft", kind = "chapter", style = "deep-dive"),
            "https://site.example.org",
            localFile = local.absolutePath,
        )
        assertTrue(runCatching { savePodcastMp3(context, unfinished, null) }.isFailure)
        assertTrue(runCatching { preparePodcastShare(context, unfinished, null) }.isFailure)

        val prefs = context.kidsPrefs()
        prefs.start("Sam", 15, "1234")
        try {
            val ready = readyPodcast(localFile = local.absolutePath)
            assertTrue(runCatching { savePodcastMp3(context, ready, null) }.isFailure)
            assertTrue(runCatching { preparePodcastShare(context, ready, null) }.isFailure)
        } finally {
            prefs.leave("1234")
        }
    }

    private fun assertShareContract(
        prepared: PreparedPodcastShare,
        expectedTitle: String,
        expectedFileName: String,
        expectedBytes: ByteArray,
    ) {
        val uri = prepared.uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.share", uri.authority)
        assertEquals("audio/mpeg", context.contentResolver.getType(uri))

        val queriedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }
        assertEquals(expectedFileName, queriedName)

        val streamedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(expectedBytes, streamedBytes)

        val send = prepared.sendIntent
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/mpeg", send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(expectedTitle, send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("$expectedTitle — from Mavis Library", send.getStringExtra(Intent.EXTRA_TEXT))
        assertNotEquals(0, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotNull(send.clipData)
        assertTrue(send.clipData!!.description.hasMimeType("audio/mpeg"))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)

        val chooser = prepared.chooserIntent
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotEquals(0, chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull(chooser.clipData)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
    }
}
