package com.mavislibrary.app.data.feeds

import com.mavislibrary.app.data.api.TechItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [body] is the post's readable text (full content when the feed carries it, else its description), for podcasts. */
 data class FeedStory(val item: TechItem, val published: Long?, val body: String = "")
 data class ParsedFeed(val title: String, val stories: List<FeedStory>, val itemCount: Int, val newest: Long?)

const val MAX_STORY_BODY = 8_000

object FeedParser {
    fun parse(xml: String): ParsedFeed {
        if (xml.toByteArray().size > MAX_FEED_BYTES || xml.contains('\u0000') ||
            Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) throw IOException("Feeds with DTDs or oversized XML are not supported.")
        try {
            val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            p.setInput(StringReader(xml.removePrefix("\uFEFF")))
            var root = ""
            var rootClosed = false
            var feedTitle = ""
            var count = 0
            var entryDepth = -1
            var title = ""; var link = ""; var guid = ""; var summary = ""; var date: Long? = null
            var full = ""; var blurb = ""
            val stories = mutableListOf<FeedStory>()
            var field = ""; var fieldDepth = -1
            val text = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.DOCDECL -> throw IOException("DTDs are not supported.")
                    XmlPullParser.START_TAG -> {
                        val name = p.name
                        if (root.isEmpty()) {
                            root = name
                            if (root !in setOf("rss", "feed", "RDF")) throw IOException("Not an RSS or Atom feed.")
                        }
                        if (name in setOf("item", "entry") && entryDepth < 0) {
                            entryDepth = p.depth; count++
                            title = ""; link = ""; guid = ""; summary = ""; date = null; full = ""; blurb = ""
                        } else if (name == "link" && entryDepth > 0 && p.depth == entryDepth + 1 &&
                            p.getAttributeValue(null, "href") != null) {
                            if (p.getAttributeValue(null, "rel") in listOf(null, "alternate")) link = p.getAttributeValue(null, "href")
                        } else if (fieldDepth < 0 && ((entryDepth > 0 && p.depth == entryDepth + 1) ||
                            (entryDepth < 0 && name == "title" && p.depth <= 3))) {
                            field = name; fieldDepth = p.depth; text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (fieldDepth > 0) text.append(p.text.orEmpty())
                    XmlPullParser.END_TAG -> {
                        if (p.depth == 1) rootClosed = true
                        if (p.depth == fieldDepth) {
                            val value = text.toString().trim()
                            if (entryDepth < 0 && field == "title" && feedTitle.isBlank()) feedTitle = clean(value)
                            else when (field) {
                                "title" -> title = clean(value)
                                "link" -> if (link.isBlank()) link = value
                                "guid", "id" -> guid = value
                                "description", "summary", "encoded", "content" -> {
                                    if (summary.isBlank()) summary = clean(value).take(200)
                                    if (field == "encoded" || field == "content") { if (full.isBlank()) full = readable(value) }
                                    else if (blurb.isBlank()) blurb = readable(value)
                                }
                                "pubDate", "published", "updated", "date" -> if (date == null) date = parseDate(value)
                            }
                            fieldDepth = -1
                        }
                        if (p.depth == entryDepth) {
                            val safeLink = runCatching { UrlPolicy.normalize(link) }.getOrNull()
                            // Item links must explicitly be HTTPS, never upgrade publisher HTTP links.
                            if (link.startsWith("https://") && safeLink != null && title.isNotBlank()) {
                                stories += FeedStory(TechItem(stableId(guid.ifBlank { safeLink }), title, safeLink, summary = summary), date, full.ifBlank { blurb })
                            }
                            entryDepth = -1
                        }
                    }
                }
                p.nextToken()
            }
            if (root.isEmpty() || !rootClosed) throw IOException("Invalid or incomplete feed.")
            val name = feedTitle.ifBlank { "Tech feed" }
            return ParsedFeed(name, stories.distinctBy { it.item.id }.sortedByDescending { it.published ?: Long.MIN_VALUE }
                .take(40).map { it.copy(item = it.item.copy(source = name)) }, count, stories.mapNotNull { it.published }.maxOrNull())
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("This feed contains invalid or incomplete XML.", e) }
    }

    private fun clean(value: String) = decodeEntities(value.replace(Regex("<[^>]*>"), " "))
        .replace(Regex("\\s+"), " ").trim()

    /** Like [clean], but keeps paragraph breaks and drops scripts/styles, capped at [MAX_STORY_BODY]. */
    private fun readable(value: String): String {
        val text = value.replace(Regex("<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<(br|/p|/div|/h[1-6]|/li|/blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), " ")
        return decodeEntities(text).lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            .joinToString("\n").take(MAX_STORY_BODY).trim()
    }

    /** Numeric (&#128202; / &#x1F4CA;) and common named entities; &amp; last so "&amp;lt;" stays literal text. */
    internal fun decodeEntities(value: String): String {
        val numeric = Regex("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});").replace(value) { m ->
            val raw = m.groupValues[1]
            val cp = runCatching { if (raw[0] == 'x' || raw[0] == 'X') raw.substring(1).toInt(16) else raw.toInt() }.getOrNull()
            if (cp == null || cp !in 0x20..0x10FFFF || cp in 0xD800..0xDFFF) "" else String(Character.toChars(cp))
        }
        return numeric.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
    private fun parseDate(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)).toInstant().toEpochMilli() }.getOrNull()
    fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
