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
@Serializable data class DevotionalRule(val id: String, val kind: String, val name: String,
    val url: String? = null, val topic: String? = null, val surprise: Boolean = false, val enabled: Boolean = true)
@Serializable data class DevotionalEpisode(val jobId: String, val title: String, val reference: String, val createdAt: String)
@Serializable data class DevotionalResponse(val id: String, val kind: String, val name: String,
    val url: String? = null, val topic: String? = null, val surprise: Boolean = false, val enabled: Boolean = true,
    val lastEpisode: DevotionalEpisode? = null) {
    fun rule() = DevotionalRule(id, kind, name, url, topic, surprise, enabled)
}
@Serializable data class SubscriptionNotify(val telegram: Boolean = false)
/** PUT has its own model so server fields can never leak into a strict request. */
@Serializable data class SubscriptionDocument(val version: Int = 1, val sites: List<SiteRule> = emptyList(),
    val schedule: SubscriptionSchedule = SubscriptionSchedule(), val engine: String = "notebooklm",
    val rank: String = "newest", val keepDays: Int = 7, val notify: SubscriptionNotify = SubscriptionNotify(),
    val devotionals: List<DevotionalRule> = emptyList(),
    val devotionalSchedule: SubscriptionSchedule = SubscriptionSchedule(readyBy = "19:00", timezone = schedule.timezone)) {
    fun validated(): SubscriptionDocument {
        require(version == 1 && engine in setOf("notebooklm", "auto") && rank in setOf("newest", "important") && keepDays in 1..30)
        for (schedule in listOf(schedule, devotionalSchedule)) {
        require(schedule.timezone.length <= 100 && schedule.timezone.matches(Regex("[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*")))
        require(schedule.readyBy.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
        ZoneId.of(schedule.timezone)
        require(schedule.days.distinct() == schedule.days && schedule.days.all { it in subscriptionDays })
        require(!schedule.enabled || schedule.days.isNotEmpty()) { "Choose at least one day." }
        }
        fun text(value: String, max: Int) = value.isNotEmpty() && value.length <= max && value.none { it.code < 32 || it.code == 127 }
        require(devotionals.size <= 5)
        require((sites.map { it.id } + devotionals.map { it.id }).distinct().size == sites.size + devotionals.size)
        devotionals.forEach { d ->
            require(text(d.id, 100) && d.id.matches(Regex("[A-Za-z0-9_-]+")) && text(d.name, 200) && d.kind in setOf("site", "topic"))
            require(d.topic == null || d.topic.length <= 120 && d.topic.none { it.code < 32 || it.code == 127 })
            if (d.kind == "topic") require(d.surprise || !d.topic.isNullOrBlank())
            if (d.kind == "site") require(publicDevotionalUrl(d.url)) { "Use a public HTTPS devotional link." }
        }
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
    val updatedAt: String? = null, val today: DailyUsage? = null, val lastRun: SubscriptionRun? = null,
    val devotionals: List<DevotionalResponse> = emptyList(),
    val devotionalSchedule: SubscriptionSchedule = SubscriptionSchedule(readyBy = "19:00", timezone = schedule.timezone),
    val nextDevotionalAt: String? = null) {
    fun document() = SubscriptionDocument(version, sites, schedule, engine, rank, keepDays, notify, devotionals.map { it.rule() }, devotionalSchedule)
}

fun migrateSubscription(sites: List<FeedSource>, old: BriefingSettings, zone: ZoneId): SubscriptionDocument =
    SubscriptionDocument(sites = sites.mapIndexed { index, site ->
        SiteRule(site.id, site.name, site.feedUrl, site.siteUrl,
            mode = if (!site.enabled || site.podcast == SitePodcast.OFF) "off" else "top",
            count = if (!site.enabled || site.podcast == SitePodcast.OFF) null else 1,
            delivery = if (site.podcast == SitePodcast.OWN) "separate" else "briefing", priority = index, source = site.source)
    }, schedule = SubscriptionSchedule(old.enabled, old.time.toString(), subscriptionDays, zone.id))

/** A server briefing site as a local follow; keeps the server id so podsub rules keep mapping to it. */
fun SiteRule.toFeedSource() = FeedSource(id, name, feedUrl, siteUrl, 0,
    podcast = when { mode == "off" -> SitePodcast.OFF; delivery == "separate" -> SitePodcast.OWN; else -> SitePodcast.BRIEFING },
    topCount = if (mode == "off") 0 else (count ?: 1).coerceIn(1, 5), source = source)

/** Age is the episode creation age, so re-downloading never extends retention. Invalid dates are preserved. */
fun downloadExpired(createdAt: String, keepDays: Int, now: Instant): Boolean {
    require(keepDays in 1..30)
    return runCatching { Instant.parse(createdAt).isBefore(now.minusSeconds(keepDays * 86400L)) }.getOrDefault(false)
}

/** Literal private addresses and credentials are rejected locally; server also checks DNS on fetch. */
fun publicDevotionalUrl(value: String?): Boolean = runCatching {
    val u = java.net.URI(value ?: "")
    val host = u.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443) || host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return false
    if (host.contains(':')) return false
    if (host.all { it.isDigit() || it == '.' }) {
        val n = host.split('.').map { it.toIntOrNull() ?: return false }
        if (n.size != 4 || n.any { it !in 0..255 } || n[0] in listOf(0, 10, 127) || n[0] >= 224 || n[0] == 169 && n[1] == 254 || n[0] == 172 && n[1] in 16..31 || n[0] == 192 && n[1] == 168 || n[0] == 100 && n[1] in 64..127) return false
    }
    true
}.getOrDefault(false)
