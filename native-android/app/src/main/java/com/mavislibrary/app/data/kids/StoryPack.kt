package com.mavislibrary.app.data.kids

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

val STORY_VOICES = listOf("host", "dad", "grandma")
fun storyTier(age: Int): String = when (age.coerceIn(2, 10)) {
    in 2..4 -> "t1"
    in 5..7 -> "t2"
    else -> "t3"
}

@Serializable
data class StoryVoice(val id: String, val label: String, val default: Boolean = false, val preview: String? = null)
@Serializable
data class StoryTier(val id: String, val label: String, val minAge: Int, val maxAge: Int)
@Serializable
data class StoryCatalog(
    val version: Int, val credits: String, val voices: List<StoryVoice> = emptyList(),
    val tiers: List<StoryTier> = emptyList(), val stories: List<StoryEntry>,
)
@Serializable
data class StoryEntry(
    val id: String, val title: String, val ref: String, val obs: JsonElement? = null,
    val cover: String, val zip: String, val bytes: Long, val sha256: String,
    val minutes: Map<String, Double> = emptyMap(),
)
@Serializable
data class StoryPage(
    val img: String, val text: Map<String, String> = emptyMap(),
    val audio: Map<String, Map<String, String>> = emptyMap(),
)
@Serializable
data class StoryQuestion(val q: String, val choices: List<String>, val answer: Int)
@Serializable
data class Story(
    val id: String, val title: String, val ref: String, val music: String = "music.mp3",
    val pages: List<StoryPage>, val question: Map<String, StoryQuestion> = emptyMap(),
) {
    fun pagesFor(tier: String) = pages.filter { !it.text[tier].isNullOrBlank() }
}

object StoryPacks {
    private val json = Json { ignoreUnknownKeys = true }
    fun catalog(text: String): StoryCatalog = json.decodeFromString<StoryCatalog>(text).also { catalog ->
        require(catalog.version == 1)
        require(catalog.stories.map { it.id }.distinct().size == catalog.stories.size)
        catalog.stories.forEach {
            require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(it.id))
            require(it.bytes in 1..MAX_ZIP_BYTES && Regex("[a-fA-F0-9]{64}").matches(it.sha256))
            relative(it.cover); relative(it.zip)
        }
        catalog.voices.forEach { it.preview?.let(::relative) }
    }
    fun story(text: String): Story = json.decodeFromString<Story>(text).also { story ->
        require(story.pages.isNotEmpty())
        relative(story.music)
        story.pages.forEach { page ->
            relative(page.img)
            page.audio.values.forEach { tiers -> tiers.values.forEach(::relative) }
        }
        story.question.values.forEach { require(it.answer in it.choices.indices) }
    }
    fun relative(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            path.none { it == '%' || it == '?' || it == '#' || it.code < 32 })
        return path
    }
    fun file(root: File, path: String): File {
        val result = File(root, relative(path)).canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath()) && result != root.canonicalFile)
        return result
    }
    const val MAX_ZIP_BYTES = 512L * 1024 * 1024
}
