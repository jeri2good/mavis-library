package com.mavislibrary.app.data.kids

import com.mavislibrary.app.data.api.LibriVoxSection
import com.mavislibrary.app.data.api.LibriVoxVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class LongListenCatalog(
    val version: Int, val kind: String, val audioOnly: Boolean, val credits: String,
    val books: List<LongListenBook>,
)

@Serializable
data class LongListenBook(
    val id: String, val title: String, val author: String = "", val readers: List<String> = emptyList(),
    val totaltime: String = "", val totalSeconds: Long = 0, val cover: String,
    val license: String = "", val ageHint: String = "6+", val sections: List<LongListenSection>,
) {
    // A separate namespace prevents collisions with the adult audiobook cache.
    fun version() = LibriVoxVersion(id = "kids-$id", title = title, readers = readers, totalSecs = totalSeconds,
        sections = sections.mapIndexed { i, s -> LibriVoxSection(i, s.title, s.mp3, s.seconds, s.readers.joinToString()) })
}

@Serializable
data class LongListenSection(
    val title: String, val mp3: String, val seconds: Long = 0, val readers: List<String> = emptyList(),
    val bibleRef: String? = null,
)

object LongListen {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): LongListenCatalog = json.decodeFromString<LongListenCatalog>(text).also {
        require(it.version == 1 && it.kind == "longlisten" && it.audioOnly)
        require(it.books.map { b -> b.id }.distinct().size == it.books.size)
        it.books.forEach { book ->
            require(Regex("librivox-[0-9]+").matches(book.id) && book.sections.isNotEmpty())
            require(book.totalSeconds >= 0)
            archiveUrl(book.cover)
            book.sections.forEach { section -> archiveUrl(section.mp3); require(section.seconds >= 0) }
        }
    }
    private fun archiveUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host in setOf("archive.org", "www.archive.org") &&
            uri.userInfo == null && uri.port == -1 && uri.path.startsWith("/download/"))
    }
}
