package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.share.parseBibleReference

data class PodcastDraft(val kind: String = "topic", val title: String = "", val text: String = "", val url: String = "", val gutenbergId: Int? = null, val originalChars: Int = text.length, val confirmShare: Boolean = false) {
    fun request(style: String, length: String, engine: String) = PodcastRequest(kind, title.trim().ifBlank { if (kind == "article") url else text.take(100) },
        text = podcastText(text).takeIf { kind != "topic" && it.isNotBlank() }, url = url.takeIf { kind == "article" && it.isNotBlank() }?.let(::articleUrl),
        gutenbergId = gutenbergId.takeIf { kind == "book" }, query = text.takeIf { kind == "topic" }, style = style, length = length, engine = engine).validated()
    companion object {
        fun book(book: Book) = if (book.source == BookSource.GUTENBERG) PodcastDraft("book", book.title, gutenbergId = book.sourceId.toIntOrNull())
            else PodcastDraft("topic", "About ${book.title}", "Discuss ${book.title} by ${book.authorLine}. This is a topic discussion, not an analysis of supplied book text.")
        fun extracted(kind: String, title: String, text: String) = PodcastDraft(kind, title.take(300), podcastText(text), originalChars = text.length)
        fun chapter(book: String, chapter: String, text: String) = extracted("chapter", "$book - ${chapter.ifBlank { "Current section" }}", text)
        fun article(title: String, url: String) = PodcastDraft("article", title.ifBlank { url }.take(300), url = url)
    }
}
sealed interface PodcastShare {
    data class Bible(val route: String) : PodcastShare
    data class Lounge(val draft: PodcastDraft) : PodcastShare
    data object Unsupported : PodcastShare
}
fun routePodcastShare(text: String, title: String = ""): PodcastShare {
    parseBibleReference(text)?.let { return PodcastShare.Bible(it.route) }
    val trimmed = text.trim()
    val link = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(trimmed)
    if (link != null) return runCatching {
        val url = articleUrl(link.value)
        val label = title.ifBlank { trimmed.removeRange(link.range).trim() }
        PodcastShare.Lounge(PodcastDraft.article(label, url))
    }.getOrDefault(PodcastShare.Unsupported)
    if (trimmed.isBlank()) return PodcastShare.Unsupported
    return PodcastShare.Lounge(PodcastDraft.extracted("chapter", "Shared text", trimmed).copy(confirmShare = true))
}
