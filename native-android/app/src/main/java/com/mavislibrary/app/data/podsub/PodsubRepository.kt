package com.mavislibrary.app.data.podsub

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.podcast.podcastJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.time.ZoneId

interface MigrationState {
    suspend fun followed(account: String): List<String>?
    suspend fun complete(account: String, ids: List<String>)
}
class PodsubStore(private val store: DataStore<Preferences>) : MigrationState {
    private fun key(account: String) = stringPreferencesKey("podsub_migrated_v1_$account")
    override suspend fun followed(account: String): List<String>? = store.data.first()[key(account)]?.let { podcastJson.decodeFromString(it) }
    override suspend fun complete(account: String, ids: List<String>) { store.edit { it[key(account)] = podcastJson.encodeToString(ids) } }
}

/** Serialized with UI saves so an hourly sync cannot overwrite a local edit. Server remains authoritative. */
class PodsubRepository(private val api: PodsubService, private val migration: MigrationState,
    private val identity: suspend () -> String?, private val sources: suspend () -> List<FeedSource>,
    private val oldSettings: suspend () -> BriefingSettings, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val mutex = Mutex()
    suspend fun load(): SubscriptionResponse = mutex.withLock { synchronize() }
    private suspend fun synchronize(): SubscriptionResponse {
        val account = identity() ?: throw ApiException("sign_in_required", "Sign in to use Podcast Lounge", 401)
        val prior = migration.followed(account)
        val followed = sources()
        val remote = try { api.get() } catch (e: ApiException) { if (e.status == 404) null else throw e }
        val initial = migrateSubscription(followed, oldSettings(), zone())
        // An existing saved server subscription wins over legacy phone settings, including on a second phone.
        val base = remote?.takeIf { it.updatedAt != null || it.sites.isNotEmpty() }?.document() ?: initial
        val localIds = followed.map { it.id }
        val removed = prior.orEmpty().toSet() - localIds.toSet()
        val remaining = base.sites.filterNot { it.id in removed }.sortedBy { it.priority }
        val mapped = remaining.map { rule -> followed.find { it.id == rule.id }?.let {
            rule.copy(name = it.name, feedUrl = it.feedUrl, siteUrl = it.siteUrl, source = it.source)
        } ?: rule }
        val additions = followed.filter { site -> mapped.none { it.id == site.id } }.map { site ->
            // New follows always start as Top 1 / Briefing. Legacy preferences apply only during migration.
            if (prior == null) initial.sites.first { it.id == site.id } else SiteRule(site.id, site.name, site.feedUrl, site.siteUrl, source = site.source)
        }
        val next = base.copy(sites = (mapped + additions).mapIndexed { index, site -> site.copy(priority = index) }).validated()
        val result = if (prior == null || remote == null || next != remote.document()) api.put(next).let {
            it.copy(today = it.today ?: remote?.today, lastRun = it.lastRun ?: remote?.lastRun)
        } else remote
        check(identity() == account) { "Account changed. Refresh to continue." }
        migration.complete(account, localIds)
        return result
    }
    suspend fun save(document: SubscriptionDocument): SubscriptionResponse = mutex.withLock { api.put(document) }
    suspend fun today() = api.today()
    suspend fun runNow() = mutex.withLock { synchronize(); api.runNow() }
}
