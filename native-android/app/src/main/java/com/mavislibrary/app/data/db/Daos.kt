package com.mavislibrary.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books WHERE `key` = :key")
    suspend fun get(key: String): BookEntity?
}

@Dao
interface ShelfDao {
    @Upsert
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: ShelfEntryEntity): Long

    /** Saves the book's details and puts it on the shelf in one transaction. */
    @Transaction
    suspend fun addToShelf(book: BookEntity, entry: ShelfEntryEntity) {
        upsertBook(book)
        insertEntry(entry)
    }

    @Query("DELETE FROM shelf_entries WHERE bookKey = :key")
    suspend fun remove(key: String)

    @Query("SELECT bookKey FROM shelf_entries")
    fun keys(): Flow<List<String>>

    @Query("SELECT b.* FROM books b JOIN shelf_entries s ON s.bookKey = b.`key` ORDER BY s.addedAt DESC")
    fun books(): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM shelf_entries")
    suspend fun count(): Int
}

@Dao
interface CachedResponseDao {
    @Upsert
    suspend fun put(entry: CachedResponseEntity)

    @Query("SELECT * FROM cached_responses WHERE cacheKey = :key")
    suspend fun get(key: String): CachedResponseEntity?

    @Query("DELETE FROM cached_responses WHERE fetchedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int
}

@Dao
interface ReadingProgressDao {
    @Upsert
    suspend fun put(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookKey = :key")
    suspend fun get(key: String): ReadingProgressEntity?
}

@Dao
interface AudioResumeDao {
    @Upsert
    suspend fun put(position: AudioResumeEntity)

    @Query("SELECT * FROM audio_resume WHERE bookKey = :bookKey")
    suspend fun get(bookKey: String): AudioResumeEntity?

    @Query("SELECT * FROM audio_resume ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): AudioResumeEntity?
}

@Dao
interface DownloadQueueDao {
    @Insert
    suspend fun enqueue(item: DownloadQueueEntity): Long

    @Query("SELECT * FROM download_queue WHERE state = :state ORDER BY createdAt")
    suspend fun withState(state: String): List<DownloadQueueEntity>

    @Query("UPDATE download_queue SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)
}
