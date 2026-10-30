package com.mavislibrary.app.ui.lounge

import android.webkit.WebView
import com.mavislibrary.app.data.podcast.PodcastDraft
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class PickerAnnotation(val bookKey: String, val kind: String, val cfi: String, val text: String, val note: String, val chapter: String, val createdAt: Long)
fun parsePickerAnnotations(raw: String?): List<PickerAnnotation> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until minOf(array.length(), 2000)).mapNotNull { i ->
        val row = array.optJSONObject(i) ?: return@mapNotNull null
        val key = row.optString("bookKey"); val kind = row.optString("kind")
        if (key.isBlank() || kind !in setOf("note", "quote", "highlight", "wordhl", "bookmark")) return@mapNotNull null
        PickerAnnotation(key, kind, row.optString("cfi"), row.optString("text"), row.optString("note"), row.optString("chapter"), row.optLong("createdAt"))
    }.sortedByDescending { it.createdAt }
}.getOrDefault(emptyList())

fun biblePickerDraft(book: String, chapter: Int, rawVerses: List<String>): PodcastDraft {
    val reference = "${if (book == "Psalms") "Psalm" else book} $chapter"
    val text = "$reference (KJV)\n\n" + rawVerses.mapIndexed { index, verse -> "${index + 1} ${verse.replace(Regex("\\{[HG0-9,]*\\}"), "")}" }.joinToString("\n")
    return PodcastDraft.chapter("Bible", reference, text)
}
data class PickerChapter(val label: String, val text: String, val lastRead: Boolean = false, val marked: Boolean = false)
fun sortedPickerChapters(rows: List<PickerChapter>) = rows.sortedWith(compareByDescending<PickerChapter> { it.lastRead }.thenByDescending { it.marked })
fun <T> continueReadingFirst(rows: List<T>, progress: (T) -> Boolean) = rows.sortedByDescending(progress)

/** evaluateJavascript doesn't await promises: use a per-request mailbox and bounded polling. */
suspend fun pickerQuery(view: WebView?, expression: String, timeout: Long = 2500): String? = withContext(Dispatchers.Main) {
    if (view == null || !com.mavislibrary.app.web.isAppOrigin(view.url)) return@withContext null
    val slot = "__picker" + java.util.UUID.randomUUID().toString().replace("-", "")
    suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
    }
    try {
        withTimeoutOrNull(timeout) {
            evaluate("window.$slot=null;Promise.resolve().then(()=>($expression)).then(v=>{if('$slot' in window)window.$slot=JSON.stringify(v)}).catch(()=>{if('$slot' in window)window.$slot='null'});void 0")
            while (true) {
                val value = evaluate("window.$slot")
                if (value != null && value != "null") return@withTimeoutOrNull JSONArray("[$value]").optString(0).takeUnless { it == "null" }
                delay(40)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { runCatching { view.evaluateJavascript("delete window.$slot", null) } }
}

fun groupedBibleAnnotations(rows: List<PickerAnnotation>): Map<String, List<PickerAnnotation>> = rows
    .filter { it.bookKey == "bible" && it.kind in setOf("note", "quote", "highlight", "wordhl") && Regex("^[1-3]?[A-Za-z]+\\.\\d+(?:\\.\\d+)?(?:-.*)?$").matches(it.cfi) }
    .sortedByDescending { it.createdAt }
    .groupBy { it.cfi.substringBefore('-').split('.').take(2).joinToString(".") }
