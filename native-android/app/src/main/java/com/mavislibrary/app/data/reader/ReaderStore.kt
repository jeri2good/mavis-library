package com.mavislibrary.app.data.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mavislibrary.app.data.feeds.FeedStory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReaderTextSize(val label: String, val sp: Int) { S("S", 16), M("M", 18), L("L", 21), XL("XL", 24) }
enum class ReaderTheme(val label: String) { LIGHT("Light"), SEPIA("Sepia"), DARK("Dark") }
data class ReaderSettings(val size: ReaderTextSize = ReaderTextSize.M, val theme: ReaderTheme = ReaderTheme.LIGHT)

/** Reader text size and theme, persisted so every article opens the way it was last read. */
class ReaderPrefs(private val store: DataStore<Preferences>) {
    private val sizeKey = stringPreferencesKey("reader_text_size_v1")
    private val themeKey = stringPreferencesKey("reader_theme_v1")
    val settings: Flow<ReaderSettings> = store.data.map { p ->
        ReaderSettings(
            ReaderTextSize.entries.find { it.name == p[sizeKey] } ?: ReaderTextSize.M,
            ReaderTheme.entries.find { it.name == p[themeKey] } ?: ReaderTheme.LIGHT,
        )
    }
    suspend fun setSize(size: ReaderTextSize) { store.edit { it[sizeKey] = size.name } }
    suspend fun setTheme(theme: ReaderTheme) { store.edit { it[themeKey] = theme.name } }
}

/** Read article ids per site, newest last, capped at [CAP] per site. */
class ReadStateStore(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("reader_read_ids_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(value: String?): Map<String, List<String>> =
        value?.let { runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }.orEmpty()
    val read: Flow<Map<String, Set<String>>> = store.data.map { p -> decode(p[key]).mapValues { it.value.toSet() } }

    suspend fun markRead(siteId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        store.edit { p ->
            val all = decode(p[key])
            val kept = all[siteId].orEmpty().filterNot { it in ids }
            p[key] = json.encodeToString(all + (siteId to (kept + ids.distinct()).takeLast(CAP)))
        }
    }

    companion object { const val CAP = 500 }
}

fun List<FeedStory>.unread(read: Set<String>) = filter { it.item.id !in read }
