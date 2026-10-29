package com.mavislibrary.app.data.podsub

import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.SitePodcast
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

val subscriptionDays = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
@Serializable data class SiteRule(val id: String, val name: String, val feedUrl: String, val siteUrl: String,
    val mode: String = "top", val count: Int? = 1, val delivery: String = "briefing", val priority: Int = 0, val source: String = "feed")
@Serializable data class SubscriptionSchedule(val enabled: Boolean = false, val readyBy: String = "06:30",
    val days: List<String> = subscriptionDays, val timezone: String = ZoneId.systemDefault().id)
@Serializable data class SubscriptionNotify(val telegram: Boolean = false)
/** PUT has its own model so server fields can never leak into a strict request. */
@Serializable data class SubscriptionDocument(val version: Int = 1, val sites: List<SiteRule> = emptyList(),
    val schedule: SubscriptionSchedule = SubscriptionSchedule(), val engine: String = "notebooklm",
    val rank: String = "newest", val keepDays: Int = 7, val notify: SubscriptionNotify = SubscriptionNotify()) {
    fun validated(): SubscriptionDocument {
        require(version == 1 && engine in setOf("notebooklm", "auto") && rank in setOf("newest", "important") && keepDays in 1..30)
        require(schedule.readyBy.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
        ZoneId.of(schedule.timezone)
        require(schedule.days.distinct() == schedule.days && schedule.days.all { it in subscriptionDays })
        require(!schedule.enabled || schedule.days.isNotEmpty()) { "Choose at least one day." }
        require(sites.map { it.id }.distinct().size == sites.size)
        sites.forEach { require(it.source in setOf("feed", "page") && it.mode in setOf("off", "top") && (it.mode != "top" || it.count in 1..5) && it.delivery in setOf("briefing", "separate")) }
        return copy(sites = sites.map { if (it.mode == "off") it.copy(count = null) else it })
    }
}
@Serializable data class DailyUsage(val used: Int, val limit: Int, val date: String)
@Serializable data class RunEpisode(val jobId: String, val title: String, val siteIds: List<String> = emptyList())
@Serializable data class SkippedSite(val siteId: String, val reason: String)
@Serializable data class SubscriptionRun(val startedAt: String, val finishedAt: String? = null, val status: String,
    val episodes: List<RunEpisode> = emptyList(), val skipped: List<SkippedSite> = emptyList())
@Serializable data class SubscriptionResponse(val version: Int = 1, val sites: List<SiteRule> = emptyList(),
    val schedule: SubscriptionSchedule = SubscriptionSchedule(), val engine: String = "notebooklm", val rank: String = "newest",
    val keepDays: Int = 7, val notify: SubscriptionNotify = SubscriptionNotify(), val nextRunAt: String? = null,
    val updatedAt: String? = null, val today: DailyUsage? = null, val lastRun: SubscriptionRun? = null) {
    fun document() = SubscriptionDocument(version, sites, schedule, engine, rank, keepDays, notify)
}

fun migrateSubscription(sites: List<FeedSource>, old: BriefingSettings, zone: ZoneId): SubscriptionDocument =
    SubscriptionDocument(sites = sites.mapIndexed { index, site ->
        SiteRule(site.id, site.name, site.feedUrl, site.siteUrl,
            mode = if (!site.enabled || site.podcast == SitePodcast.OFF) "off" else "top",
            count = if (!site.enabled || site.podcast == SitePodcast.OFF) null else 1,
            delivery = if (site.podcast == SitePodcast.OWN) "separate" else "briefing", priority = index, source = site.source)
    }, schedule = SubscriptionSchedule(old.enabled, old.time.toString(), subscriptionDays, zone.id))

/** Age is the episode creation age, so re-downloading never extends retention. Invalid dates are preserved. */
fun downloadExpired(createdAt: String, keepDays: Int, now: Instant): Boolean {
    require(keepDays in 1..30)
    return runCatching { Instant.parse(createdAt).isBefore(now.minusSeconds(keepDays * 86400L)) }.getOrDefault(false)
}
