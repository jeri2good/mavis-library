package com.mavislibrary.app.ui.lounge

import android.net.Uri
import com.mavislibrary.app.data.podcast.SavedPodcast
import com.mavislibrary.app.data.podcast.podcastExpired

/** Chips above the record carousel. Couldn't be made only shows under All. */
enum class RecordFilter(val label: String, val shelf: PodcastShelf?) {
    NEW("New", PodcastShelf.NEW),
    CONTINUE("Continue listening", PodcastShelf.CONTINUE),
    MAKING("Being made", PodcastShelf.MAKING),
    PLAYED("Played", PodcastShelf.PLAYED),
    ALL("All", null),
}

fun defaultRecordFilter(shelves: Map<PodcastShelf, List<Episode>>) =
    if (shelves[PodcastShelf.NEW].isNullOrEmpty()) RecordFilter.ALL else RecordFilter.NEW

/** The records in the crate for [filter]; All is every episode, newest first. */
fun records(filter: RecordFilter, shelves: Map<PodcastShelf, List<Episode>>): List<Episode> =
    filter.shelf?.let { shelves[it].orEmpty() } ?: shelves.values.flatten().sortedByDescending { it.job.createdAt }

/** The crate's other records for the Up next list: the ones after [centred] first, then the ones before it, with their pager index. */
fun upNextRows(crate: List<Episode>, centred: Int): List<IndexedValue<Episode>> =
    crate.withIndex().let { all -> all.drop(centred + 1) + all.take(centred.coerceIn(0, crate.size)) }

/** Episodes this listener can see from [address] (or has downloaded), not past clean-up, newest first. */
fun visiblePodcasts(all: List<SavedPodcast>, identity: String?, address: String, cleanupDays: Int): List<SavedPodcast> =
    all.filter { (identity == "owner" || identity != null && it.accountId == identity) && (it.studio == address || it.localFile != null) && !podcastExpired(it.job, cleanupDays) }
        .sortedByDescending { it.job.createdAt }

/** [queue] with a playable address for each; episodes without one (another studio, not downloaded) drop out. */
fun playableQueue(queue: List<Episode>, uriOf: (SavedPodcast) -> Uri?): List<Pair<SavedPodcast, Uri>> =
    queue.mapNotNull { e -> uriOf(e.saved)?.let { e.saved to it } }
