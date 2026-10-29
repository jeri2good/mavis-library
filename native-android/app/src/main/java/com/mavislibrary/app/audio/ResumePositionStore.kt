package com.mavislibrary.app.audio

import com.mavislibrary.app.data.db.AudioResumeDao
import com.mavislibrary.app.data.db.AudioResumeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ResumePosition(
    val bookKey: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val speed: Float,
)

class ResumePositionStore(
    private val dao: AudioResumeDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun save(value: ResumePosition) {
        dao.put(
            AudioResumeEntity(
                bookKey = value.bookKey,
                chapterIndex = value.chapterIndex.coerceAtLeast(0),
                positionMs = value.positionMs.coerceAtLeast(0),
                speed = value.speed.coerceIn(.75f, 2f),
                updatedAt = now(),
            ),
        )
    }

    suspend fun load(bookKey: String): ResumePosition? = dao.get(bookKey)?.toModel()
    suspend fun latest(): ResumePosition? = dao.latest()?.toModel()
    /** Saved listening positions of podcasts, by resume key. */
    fun podcastPositions(): Flow<Map<String, Long>> = dao.withPrefix("podcast:").map { rows -> rows.associate { it.bookKey to it.positionMs } }
    /** When each audiobook was last listened to (started ones only), by book key. */
    fun bookActivity(): Flow<Map<String, Long>> = dao.withPrefix("").map { rows ->
        rows.filter { !it.bookKey.startsWith("podcast:") && it.positionMs > 0 }.associate { it.bookKey to it.updatedAt }
    }

    private fun AudioResumeEntity.toModel() = ResumePosition(bookKey, chapterIndex, positionMs, speed)
}
