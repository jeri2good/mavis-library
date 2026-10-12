package com.mavislibrary.app.data.feeds

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FeedSource(val id: String, val name: String, val feedUrl: String, val siteUrl: String, val addedAt: Long, val enabled: Boolean = true)

class FeedStore(private val store: DataStore<Preferences>, private val clock: () -> Long = System::currentTimeMillis) {
    private val key = stringPreferencesKey("my_tech_sites_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(value: String?) = value?.let { runCatching { json.decodeFromString<List<FeedSource>>(it) }.getOrNull() }.orEmpty()
    val sources = store.data.map { decode(it[key]) }

    suspend fun add(found: DiscoveryResult.Found): FeedSource {
        val feedUrl = UrlPolicy.normalize(found.feedUrl)
        val siteUrl = UrlPolicy.normalize(found.siteUrl)
        var result: FeedSource? = null
        store.edit { prefs ->
            val old = decode(prefs[key])
            val existing = old.find { it.feedUrl == feedUrl }
            result = existing?.copy(enabled = true) ?: FeedSource(FeedParser.stableId(feedUrl), found.title.take(80), feedUrl, siteUrl, clock())
            require(existing != null || old.size < 20) { "You can add up to 20 sites. Remove one to add another." }
            prefs[key] = json.encodeToString(if (existing != null) old.map { if (it.id == existing.id) result!! else it } else old + result!!)
        }
        return result!!
    }
    suspend fun rename(id: String, name: String) {
        require(name.trim().isNotEmpty()) { "Enter a name." }
        change { all -> all.map { if (it.id == id) it.copy(name = name.trim().take(80)) else it } }
    }
    suspend fun setEnabled(id: String, enabled: Boolean) = change { all -> all.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    suspend fun remove(id: String) = change { all -> all.filterNot { it.id == id } }
    private suspend fun change(update: (List<FeedSource>) -> List<FeedSource>) {
        store.edit { it[key] = json.encodeToString(update(decode(it[key]))) }
    }
}
