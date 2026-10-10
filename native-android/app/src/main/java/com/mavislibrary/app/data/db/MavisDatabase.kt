package com.mavislibrary.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ShelfEntryEntity::class,
        CachedResponseEntity::class,
        ReadingProgressEntity::class,
        DownloadQueueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MavisDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun shelf(): ShelfDao
    abstract fun cachedResponses(): CachedResponseDao
    abstract fun readingProgress(): ReadingProgressDao
    abstract fun downloads(): DownloadQueueDao

    companion object {
        fun create(context: Context): MavisDatabase =
            Room.databaseBuilder(context, MavisDatabase::class.java, "mavis-library.db").build()
    }
}
