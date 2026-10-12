package com.mavislibrary.app.web

import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.podcast.MAX_PODCAST_TEXT
import org.json.JSONObject

data class PodcastWebShortcut(val draft: PodcastDraft, val openArticle: Boolean = false) {
    companion object {
        fun parse(payload: String): PodcastWebShortcut {
            require(payload.length <= 3_000_000) { "This source is too large." }
            val data = JSONObject(payload)
            val draft = when (data.getString("kind")) {
                "chapter" -> PodcastDraft.chapter(data.getString("book"), data.getString("chapter"), data.getString("text"))
                    .let { it.copy(originalChars = maxOf(it.originalChars, data.optInt("originalChars", it.originalChars))) }
                "article" -> PodcastDraft.article(data.optString("title"), data.getString("url"))
                "book" -> PodcastDraft("book", data.getString("title").take(300), gutenbergId = data.getInt("gutenbergId"))
                else -> error("Choose a chapter or article.")
            }
            require(draft.text.length <= MAX_PODCAST_TEXT)
            draft.request("deep-dive", "default", "auto")
            return PodcastWebShortcut(draft, draft.kind == "article" && data.optString("action") == "openArticle")
        }
    }
}
