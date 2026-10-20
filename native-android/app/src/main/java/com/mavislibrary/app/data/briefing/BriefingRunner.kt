package com.mavislibrary.app.data.briefing

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.feeds.FeedLoad
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.SitePodcast
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastPreferences
import com.mavislibrary.app.data.podcast.PodcastRequest
import com.mavislibrary.app.data.podcast.SavedPodcast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.ZoneId

sealed interface BriefingOutcome {
    data class Started(val titles: List<String>) : BriefingOutcome
    data object NothingNew : BriefingOutcome
    data object NoSites : BriefingOutcome
    /** Sign-in, access, or limits: retrying soon won't help. */
    data class Blocked(val message: String) : BriefingOutcome
}

/**
 * One briefing pass: read each podcast-enabled site, pick its new posts and ask the studio for the episodes.
 * Throws when nothing could be reached, so the caller can retry later.
 */
class BriefingRunner(
    private val sources: suspend () -> List<FeedSource>,
    private val load: suspend (FeedSource) -> FeedLoad,
    private val store: BriefingStore,
    private val create: suspend (PodcastRequest) -> SavedPodcast,
    private val defaults: suspend () -> PodcastPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun run(): BriefingOutcome {
        val now = clock()
        val followed = sources()
        val sites = followed.filter { it.enabled && it.podcast != SitePodcast.OFF }
        if (sites.isEmpty()) return done(now, "None of your sites are set to make podcasts.", BriefingOutcome.NoSites)
        val loads = sites.map { it to load(it) }
        if (loads.all { (_, feed) -> feed.stories.isEmpty() && feed.note != null }) throw IOException("Couldn't reach any of your sites.")
        val cursors = store.cursors()
        val fresh = loads.map { (site, feed) -> SitePosts(site, Briefing.newPosts(feed.stories, cursors[site.id], now)) }
        val keep = followed.map { it.id }.toSet()
        val stories = loads.associate { (site, feed) -> site.id to feed.stories }
        fun moveOn(of: List<FeedSource>) = of.associate { it.id to Briefing.advance(cursors[it.id], stories[it.id].orEmpty()) }
        // Quiet sites move on too, so a first look's older posts aren't picked up later.
        store.advance(moveOn(fresh.filter { it.posts.isEmpty() && stories[it.site.id].orEmpty().isNotEmpty() }.map { it.site }), keep)
        val prefs = defaults()
        val plan = Briefing.plan(fresh, now, zone(), prefs.style, prefs.length, prefs.engine)
        if (plan.isEmpty()) return done(now, "Nothing new from your sites since the last check.", BriefingOutcome.NothingNew)
        val started = mutableListOf<String>()
        for (episode in plan) {
            val saved = try { create(episode.request) } catch (e: CancellationException) { throw e } catch (e: ApiException) {
                if (e.status !in setOf(401, 403, 429)) throw e
                val message = when (e.status) {
                    401 -> "Sign in under Settings → Account so your sites can become podcasts."
                    403 -> "Your account needs podcast access. Request it under Settings → Account."
                    else -> "${e.message}. The rest waits for the next briefing."
                }
                return done(now, message, if (started.isEmpty()) BriefingOutcome.Blocked(message) else BriefingOutcome.Started(started))
            }
            store.addPending(PendingEpisode(saved.job.id, episode.request.title, now))
            store.advance(moveOn(episode.sites.map { it.site }), keep)
            started += episode.request.title
        }
        return done(now, if (started.size == 1) "Started “${started.single()}”." else "Started ${started.size} podcasts: ${started.joinToString("; ")}.", BriefingOutcome.Started(started))
    }

    private suspend fun done(now: Long, message: String, outcome: BriefingOutcome): BriefingOutcome {
        store.setStatus(now, message)
        return outcome
    }
}

/** Follows up on requested episodes: downloads and announces finished ones, reports failures. Returns true while any are still being made. */
class BriefingChecker(
    private val store: BriefingStore,
    private val check: suspend (String) -> PodcastJob,
    private val download: suspend (String) -> Unit,
    private val notify: (PendingEpisode, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): Boolean {
        for (episode in store.pending.first()) {
            val job = try { check(episode.id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is ApiException && e.status == 404) { store.removePending(episode.id); continue }
                null
            }
            val message = when {
                job != null && job.status == "ready" -> {
                    val saved = try { download(episode.id); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    listOfNotNull("Ready to play", job.durationSec?.let { "${(it + 30) / 60} min" }, if (saved) "saved for offline listening" else null).joinToString(" · ")
                }
                job != null && job.status == "failed" -> "Couldn't be made: " + (job.error ?: "the studio reported a problem.")
                clock() - episode.createdAt > GIVE_UP_MS -> "Still not finished after 6 hours. Check Podcast Lounge later."
                else -> null
            } ?: continue
            notify(episode, message)
            store.removePending(episode.id)
        }
        return store.pending.first().isNotEmpty()
    }

    companion object { const val GIVE_UP_MS = 6 * 3_600_000L }
}
