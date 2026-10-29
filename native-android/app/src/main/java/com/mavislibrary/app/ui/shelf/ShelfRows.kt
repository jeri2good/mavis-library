package com.mavislibrary.app.ui.shelf

import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.reader.unread
import com.mavislibrary.app.data.stats.ReadingStats
import java.time.ZoneId

/**
 * Index math for a shelf row that scrolls forever: a huge virtual list, started in the middle on item 0,
 * where each virtual index maps back with a floor modulo. Rows that fit on screen don't loop.
 */
object CircularRow {
    const val VIRTUAL_COUNT = Int.MAX_VALUE / 2

    /** How many items of [itemPx] with [spacingPx] gaps fit fully in [widthPx]. */
    fun fits(widthPx: Float, itemPx: Float, spacingPx: Float): Int =
        if (itemPx <= 0f) Int.MAX_VALUE else ((widthPx + spacingPx) / (itemPx + spacingPx)).toInt().coerceAtLeast(0)

    fun loops(count: Int, fits: Int) = count > fits

    fun itemCount(count: Int, loops: Boolean) = if (loops && count > 0) VIRTUAL_COUNT else count

    fun startIndex(count: Int, loops: Boolean) = if (!loops || count <= 0) 0 else (VIRTUAL_COUNT / 2).let { it - it % count }

    fun realIndex(index: Int, count: Int) = Math.floorMod(index, count)

    /** Unique even when the same item shows up again further along the loop. */
    fun key(index: Int, itemKey: String) = "$index|$itemKey"
}

/** A followed site, drawn as a book on the Sites shelf. */
data class SiteBook(val id: String, val name: String, val siteUrl: String, val unread: Int)

sealed interface ShelfRow {
    val id: String
    val label: String

    data class Mine(val books: List<Book>, val loading: Boolean) : ShelfRow {
        override val id = "mine"
        override val label = "My books"
    }
    data class Continue(val books: List<Book>) : ShelfRow {
        override val id = "continue"
        override val label = "Continue reading"
    }
    data class Popular(val shelf: PopularShelf) : ShelfRow {
        override val id = shelf.id
        override val label = shelf.label
    }
    data class Sites(val sites: List<SiteBook>) : ShelfRow {
        override val id = "sites"
        override val label = "Sites"
    }
}

/** My books, Continue reading (only with real progress), the curated rows, then followed sites. */
fun shelfRows(state: ShelfUiState): List<ShelfRow> = buildList {
    add(ShelfRow.Mine(state.books, state.loading))
    state.continueReading.takeIf { it.isNotEmpty() }?.let { add(ShelfRow.Continue(it)) }
    state.popular.forEach { add(ShelfRow.Popular(it)) }
    state.sites.takeIf { it.isNotEmpty() }?.let { add(ShelfRow.Sites(it)) }
}

/** Saved books with reading or listening activity, most recent first. */
fun continueReading(saved: List<Book>, activity: Map<String, Long>): List<Book> =
    saved.filter { it.key in activity }.sortedByDescending { activity.getValue(it.key) }

/**
 * Last activity per book key: reading time logged by the reader (day precision, `book:<key>` entries)
 * and native audiobook positions (`updatedAt`). Podcasts and the Bible are left out.
 */
fun readingActivity(stats: ReadingStats, listened: Map<String, Long>, zone: ZoneId = ZoneId.systemDefault()): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    fun mark(key: String, at: Long) { if (at > (result[key] ?: Long.MIN_VALUE)) result[key] = at }
    stats.days.forEach { (date, day) ->
        if (day.milliseconds <= 0) return@forEach
        val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
        day.books.filter { it.startsWith("book:") }.forEach { mark(it.removePrefix("book:"), at) }
    }
    listened.forEach { (key, at) -> mark(key, at) }
    return result
}

fun siteBooks(sources: List<FeedSource>, posts: Map<String, List<FeedStory>>, read: Map<String, Set<String>>): List<SiteBook> =
    sources.map { SiteBook(it.id, it.name, it.siteUrl, posts[it.id].orEmpty().unread(read[it.id].orEmpty()).size) }
