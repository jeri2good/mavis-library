package com.mavislibrary.app.audio

import com.mavislibrary.app.data.db.AudioResumeDao
import com.mavislibrary.app.data.db.AudioResumeEntity

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

    private fun AudioResumeEntity.toModel() = ResumePosition(bookKey, chapterIndex, positionMs, speed)
}
