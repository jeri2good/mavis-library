package com.mavislibrary.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookFormat
import com.mavislibrary.app.data.model.BookSource

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val key: String,
    val source: String,
    val sourceId: String,
    val title: String,
    /** Unit-separator joined, so names with commas survive. */
    val authors: String,
    val coverUrl: String?,
    /** "label|free" entries, unit-separator joined. */
    val formats: String,
    val year: Int?,
    val languages: String,
    val epubUrl: String?,
    val sourceUrl: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "shelf_entries",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["key"], childColumns = ["bookKey"], onDelete = ForeignKey.CASCADE)],
)
data class ShelfEntryEntity(
    @PrimaryKey val bookKey: String,
    /** "want" for now; "reading" / "finished" arrive with the native shelf (M3). */
    val status: String = "want",
    val addedAt: Long,
)

/** Raw JSON from an API call, keyed by endpoint + parameters. */
@Entity(tableName = "cached_responses")
data class CachedResponseEntity(
    @PrimaryKey val cacheKey: String,
    val body: String,
    val fetchedAt: Long,
)

/** Stub for M2/M3: where the reader or player left off. */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookKey: String,
    val locator: String?,
    val fraction: Float,
    val updatedAt: Long,
)

/** Durable native-player position, separate from the EPUB reader's locator. */
@Entity(tableName = "audio_resume")
data class AudioResumeEntity(
    @PrimaryKey val bookKey: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val speed: Float,
    val updatedAt: Long,
)

/** Queue for background downloads (Wi-Fi only). Filled from M3. */
@Entity(tableName = "download_queue", indices = [Index("bookKey"), Index("state")])
data class DownloadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookKey: String,
    val url: String,
    val kind: String,
    val state: String = STATE_QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    val createdAt: Long,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
    }
}

private const val SEP = "\u001F"

fun Book.toEntity(now: Long) = BookEntity(
    key = key,
    source = source.name,
    sourceId = sourceId,
    title = title,
    authors = authors.joinToString(SEP),
    coverUrl = coverUrl,
    formats = formats.joinToString(SEP) { "${it.label}|${it.free}" },
    year = year,
    languages = languages.joinToString(SEP),
    epubUrl = epubUrl,
    sourceUrl = sourceUrl,
    updatedAt = now,
)

fun BookEntity.toBook() = Book(
    key = key,
    source = runCatching { BookSource.valueOf(source) }.getOrDefault(BookSource.GUTENBERG),
    sourceId = sourceId,
    title = title,
    authors = authors.split(SEP).filter { it.isNotEmpty() },
    coverUrl = coverUrl,
    formats = formats.split(SEP).filter { it.isNotEmpty() }.map {
        BookFormat(it.substringBeforeLast('|'), it.substringAfterLast('|') == "true")
    },
    year = year,
    languages = languages.split(SEP).filter { it.isNotEmpty() },
    epubUrl = epubUrl,
    sourceUrl = sourceUrl,
)
