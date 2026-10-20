package com.mavislibrary.app.data.briefing

import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.feeds.SitePodcast
import com.mavislibrary.app.data.podcast.PodcastRequest
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Post IDs a site's podcasts have already covered (or passed over). */
@Serializable data class SiteCursor(val seen: List<String> = emptyList())

data class SitePosts(val site: FeedSource, val posts: List<FeedStory>)

/** One podcast to request, and the sites whose cursors move on once the studio accepts it. */
data class PlannedEpisode(val request: PodcastRequest, val sites: List<SitePosts>)

/** Turns new posts from followed sites into podcast requests: one daily briefing plus per-site episodes. */
object Briefing {
    const val MAX_POSTS_PER_SITE = 6
    const val MAX_POSTS = 20
    const val MAX_OWN_EPISODES = 4
    const val MAX_TEXT = 120_000
    /** A lone post with less text than this is sent as its link, so the studio reads the whole article. */
    const val THIN_POST = 1_000
    private const val FIRST_LOOK_MS = 36 * 3_600_000L
    private const val MAX_AGE_MS = 7 * 24 * 3_600_000L
    private const val SEEN_LIMIT = 200
    private val longDay = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val shortDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun newPosts(stories: List<FeedStory>, cursor: SiteCursor?, now: Long): List<FeedStory> {
        if (cursor != null) return stories.filter { it.item.id !in cursor.seen && (it.published == null || it.published > now - MAX_AGE_MS) }
        // A site's first look takes only recent posts, never its whole archive.
        if (stories.none { it.published != null }) return stories.take(3)
        return stories.filter { it.published != null && it.published > now - FIRST_LOOK_MS }
    }

    /** Everything the feed lists now counts as seen, so passed-over older posts don't surface later. */
    fun advance(cursor: SiteCursor?, stories: List<FeedStory>) =
        SiteCursor((stories.map { it.item.id } + cursor?.seen.orEmpty()).distinct().take(SEEN_LIMIT))

    fun plan(sites: List<SitePosts>, now: Long, zone: ZoneId, style: String, length: String, engine: String): List<PlannedEpisode> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val active = sites.filter { it.posts.isNotEmpty() }.map { it.copy(posts = it.posts.take(MAX_POSTS_PER_SITE)) }
        val episodes = mutableListOf<PlannedEpisode>()
        val briefing = active.filter { it.site.podcast == SitePodcast.BRIEFING }
        if (briefing.isNotEmpty()) {
            val posts = briefing.flatMap { site -> site.posts.map { site.site to it } }
                .sortedByDescending { it.second.published ?: Long.MIN_VALUE }.take(MAX_POSTS)
            val heading = "Daily briefing for ${longDay.format(today)}: ${count(posts.size)} from ${names(posts.map { it.first.name }.distinct())}."
            episodes += PlannedEpisode(PodcastRequest("chapter", "Daily briefing · ${longDay.format(today)}", text = text(heading, posts, zone),
                style = style, length = length, engine = engine), briefing)
        }
        active.filter { it.site.podcast == SitePodcast.OWN }.take(MAX_OWN_EPISODES).forEach { site ->
            val lone = site.posts.singleOrNull()
            val title = (if (lone != null) "${site.site.name}: ${lone.item.title}" else "${site.site.name} · ${shortDay.format(today)}").take(300)
            val request = if (lone != null && lone.body.length < THIN_POST) PodcastRequest("article", title, url = lone.item.url, style = style, length = length, engine = engine)
                else PodcastRequest("chapter", title, text = text("New from ${site.site.name} on ${longDay.format(today)}: ${count(site.posts.size)}.",
                    site.posts.map { site.site to it }, zone), style = style, length = length, engine = engine)
            episodes += PlannedEpisode(request, listOf(site))
        }
        return episodes
    }

    fun text(heading: String, posts: List<Pair<FeedSource, FeedStory>>, zone: ZoneId): String {
        val out = StringBuilder(heading).append('\n')
            .append("These are new posts from websites the listener follows. Cover each post, say which site it came from, and keep to what the posts say.\n")
        for ((index, entry) in posts.withIndex()) {
            val (site, story) = entry
            val block = buildString {
                append("\nPost ${index + 1} of ${posts.size} · ${site.name}\n")
                append("Title: ${story.item.title}\n")
                story.published?.let { append("Published: ${longDay.format(Instant.ofEpochMilli(it).atZone(zone))}\n") }
                append("Link: ${story.item.url}\n\n")
                append(story.body.ifBlank { story.item.summary }.ifBlank { "(The site gave no text for this post.)" }).append('\n')
            }
            if (out.length + block.length > MAX_TEXT) break
            out.append(block)
        }
        return out.toString().trim()
    }

    private fun count(n: Int) = if (n == 1) "1 new post" else "$n new posts"
    private fun names(all: List<String>) = when (all.size) {
        1 -> all[0]
        2 -> "${all[0]} and ${all[1]}"
        else -> all.dropLast(1).joinToString(", ") + ", and " + all.last()
    }
}
