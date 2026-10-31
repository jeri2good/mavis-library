package com.mavislibrary.app.work

import android.content.Context
import android.net.Uri
import com.mavislibrary.app.data.podcast.PodcastDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val allowedVerseRoutes: Set<String> by lazy {
    (1..VerseOfDay.size).mapTo(linkedSetOf()) { VerseOfDay.forDay(it).route }
}

fun widgetDestinationAllowed(value: String): Boolean {
    if (value in setOf("shelf", "lounge", "updates", "new-podcast")) return true
    if (value in allowedVerseRoutes) return true
    if (value.startsWith("lesson:") && value.removePrefix("lesson:") in allowedVerseRoutes) return true
    if (!value.startsWith("read:") && !value.startsWith("listen:")) return false
    val encoded = value.substringAfter(':')
    if (encoded.isBlank() || encoded.length > 900 || encoded.any { it.code < 32 }) return false
    val decoded = runCatching { Uri.decode(encoded) }.getOrNull() ?: return false
    return decoded.isNotBlank() && decoded.length <= 500 && decoded.none { it.code < 32 }
}

/** Uses the exact bundled chapter builder used by the Bible mic/picker; never creates a job itself. */
suspend fun widgetLessonDraft(context: Context, route: String): PodcastDraft? = withContext(Dispatchers.IO) {
    val verse = (1..VerseOfDay.size).map(VerseOfDay::forDay).find { it.route == route } ?: return@withContext null
    val bookId = route.removePrefix("#/bible/").substringBefore('/')
    runCatching {
        val chapter = JSONObject(context.assets.open("web/bible/kjv/$bookId.json").bufferedReader().use { it.readText() })
            .getJSONArray("chapters").getJSONArray(verse.chapter - 1)
        com.mavislibrary.app.ui.lounge.biblePickerDraft(verse.book, verse.chapter, (0 until chapter.length()).map(chapter::getString))
    }.getOrNull()
}
