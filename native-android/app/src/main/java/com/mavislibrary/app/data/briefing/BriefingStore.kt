package com.mavislibrary.app.data.briefing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime

private val ENABLED = booleanPreferencesKey("briefing_enabled")
private val TIME = stringPreferencesKey("briefing_time")
private val CURSORS = stringPreferencesKey("briefing_cursors")
private val PENDING = stringPreferencesKey("briefing_pending")
private val STATUS = stringPreferencesKey("briefing_status")
val DEFAULT_BRIEFING_TIME: LocalTime = LocalTime.of(6, 30)

data class BriefingSettings(val enabled: Boolean = false, val time: LocalTime = DEFAULT_BRIEFING_TIME)
/** A podcast the briefing asked for and is still waiting on, to download and announce when ready. */
@Serializable data class PendingEpisode(val id: String, val title: String, val createdAt: Long)
@Serializable data class BriefingStatus(val at: Long, val message: String)

class BriefingStore(private val store: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun readCursors(p: Preferences) = p[CURSORS]?.let { runCatching { json.decodeFromString<Map<String, SiteCursor>>(it) }.getOrNull() }.orEmpty()
    private fun readPending(p: Preferences) = p[PENDING]?.let { runCatching { json.decodeFromString<List<PendingEpisode>>(it) }.getOrNull() }.orEmpty()

    val settings: Flow<BriefingSettings> = store.data.map { p ->
        BriefingSettings(p[ENABLED] ?: false, p[TIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: DEFAULT_BRIEFING_TIME)
    }
    val status: Flow<BriefingStatus?> = store.data.map { p -> p[STATUS]?.let { runCatching { json.decodeFromString<BriefingStatus>(it) }.getOrNull() } }
    val pending: Flow<List<PendingEpisode>> = store.data.map(::readPending)

    suspend fun setEnabled(enabled: Boolean) { store.edit { it[ENABLED] = enabled } }
    suspend fun setTime(time: LocalTime) { store.edit { it[TIME] = time.withSecond(0).withNano(0).toString() } }
    suspend fun cursors(): Map<String, SiteCursor> = readCursors(store.data.first())
    /** Saves [updates] and forgets cursors for sites no longer followed. */
    suspend fun advance(updates: Map<String, SiteCursor>, followed: Set<String>) {
        store.edit { p -> p[CURSORS] = json.encodeToString((readCursors(p) + updates).filterKeys { it in followed }) }
    }
    suspend fun addPending(episode: PendingEpisode) {
        store.edit { p -> p[PENDING] = json.encodeToString((readPending(p).filterNot { it.id == episode.id } + episode).takeLast(20)) }
    }
    suspend fun removePending(id: String) { store.edit { p -> p[PENDING] = json.encodeToString(readPending(p).filterNot { it.id == id }) } }
    suspend fun setStatus(at: Long, message: String) { store.edit { it[STATUS] = json.encodeToString(BriefingStatus(at, message)) } }
}
