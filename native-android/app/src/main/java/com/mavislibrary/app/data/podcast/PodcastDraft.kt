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
        fun extracted(kind: String, title: String, text: String) = PodcastDraft(kind, title, podcastText(text), originalChars = text.length)
    }
}
sealed interface PodcastShare {
    data class Bible(val route: String) : PodcastShare
    data class Lounge(val draft: PodcastDraft) : PodcastShare
    data object Unsupported : PodcastShare
}
fun routePodcastShare(text: String): PodcastShare {
    parseBibleReference(text)?.let { return PodcastShare.Bible(it.route) }
    val trimmed = text.trim()
    if (trimmed.startsWith("https://", true)) return runCatching { PodcastShare.Lounge(PodcastDraft("article", url = articleUrl(trimmed), confirmShare = true)) }.getOrDefault(PodcastShare.Unsupported)
    if (trimmed.startsWith("http://", true) || trimmed.isBlank()) return PodcastShare.Unsupported
    return PodcastShare.Lounge(PodcastDraft.extracted("article", "Shared text", trimmed).copy(confirmShare = true))
}
