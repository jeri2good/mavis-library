package com.mavislibrary.app.data.podcast

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.data.secure.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

private val ADDRESS = stringPreferencesKey("podcast_studio_address")
private val STYLE = stringPreferencesKey("podcast_default_style")
private val LENGTH = stringPreferencesKey("podcast_default_length")
private val ENGINE = stringPreferencesKey("podcast_default_engine")
private val CLEANUP = intPreferencesKey("podcast_cleanup_days")
private val LIBRARY = stringPreferencesKey("podcast_local_library")

data class PodcastPreferences(val address: String = "", val style: String = "deep-dive", val length: String = "default", val engine: String = "auto", val cleanupDays: Int = 0)
interface PodcastLibrary {
    val library: Flow<List<SavedPodcast>>
    suspend fun save(podcast: SavedPodcast)
    suspend fun remove(id: String, studio: String)
}
class PodcastSettings(private val store: DataStore<Preferences>, private val secrets: SecureStore) : PodcastLibrary {
    private val mutex = Mutex()
    val preferences: Flow<PodcastPreferences> = store.data.map { p -> PodcastPreferences(p[ADDRESS].orEmpty(), p[STYLE] ?: "deep-dive", p[LENGTH] ?: "default", p[ENGINE] ?: "auto", p[CLEANUP] ?: 0) }
    private fun decode(p: Preferences) = runCatching { podcastJson.decodeFromString<List<SavedPodcast>>(p[LIBRARY] ?: "[]") }.getOrDefault(emptyList())
    override val library = store.data.map(::decode)
    suspend fun address() = preferences.first().address
    suspend fun tokenFor(base: String): String? = mutex.withLock { if (base == address()) secrets.read(Secret.PODCAST_STUDIO_TOKEN) else null }
    suspend fun configure(value: String, token: String = ""): Boolean = mutex.withLock {
        val normalized = if (value.isBlank()) "" else StudioAddress.parse(value).toString().trimEnd('/')
        require(token.length <= 4096 && (token.isBlank() || token.trim().all { it.code in 33..126 })) { "Paste a valid studio key (plain ASCII, without spaces or line breaks)." }
        if (normalized != address()) secrets.clear(Secret.PODCAST_STUDIO_TOKEN)
        store.edit { it[ADDRESS] = normalized }
        if (token.isBlank()) true else secrets.write(Secret.PODCAST_STUDIO_TOKEN, token.trim())
    }
    suspend fun clearToken() = mutex.withLock { secrets.clear(Secret.PODCAST_STUDIO_TOKEN) }
    suspend fun setDefaults(style: String, length: String, engine: String) {
        require(style in podcastStyles && length in podcastLengths && engine in setOf("auto", "local", "notebooklm"))
        store.edit { it[STYLE] = style; it[LENGTH] = length; it[ENGINE] = engine }
    }
    suspend fun setCleanupDays(days: Int) { require(days in 0..3650); store.edit { it[CLEANUP] = days } }
    override suspend fun save(podcast: SavedPodcast) {
        store.edit { p ->
            if (podcastExpired(podcast.job, p[CLEANUP] ?: 0)) return@edit
            val old = decode(p).find { it.key == podcast.key }
            val merged = podcast.copy(localFile = podcast.localFile ?: old?.localFile, request = podcast.request ?: old?.request)
            p[LIBRARY] = podcastJson.encodeToString(listOf(merged) + decode(p).filterNot { it.key == merged.key })
        }
    }
    suspend fun clearDownload(id: String, studio: String) {
        store.edit { p -> p[LIBRARY] = podcastJson.encodeToString(decode(p).map {
            if (it.job.id == id && it.studio == studio) it.copy(localFile = null) else it
        }) }
    }
    override suspend fun remove(id: String, studio: String) {
        store.edit { p -> p[LIBRARY] = podcastJson.encodeToString(decode(p).filterNot { it.job.id == id && it.studio == studio }) }
    }
}
