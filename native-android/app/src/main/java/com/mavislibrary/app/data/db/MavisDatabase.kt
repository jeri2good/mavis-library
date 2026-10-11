package com.mavislibrary.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ShelfEntryEntity::class,
        CachedResponseEntity::class,
        ReadingProgressEntity::class,
        AudioResumeEntity::class,
        DownloadQueueEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MavisDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun shelf(): ShelfDao
    abstract fun cachedResponses(): CachedResponseDao
    abstract fun readingProgress(): ReadingProgressDao
    abstract fun audioResume(): AudioResumeDao
    abstract fun downloads(): DownloadQueueDao

    companion object {
        fun create(context: Context): MavisDatabase =
            Room.databaseBuilder(context, MavisDatabase::class.java, "mavis-library.db").build()
    }
}
