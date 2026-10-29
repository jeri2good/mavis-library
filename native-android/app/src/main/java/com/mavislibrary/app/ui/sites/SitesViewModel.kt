package com.mavislibrary.app.ui.sites

import com.mavislibrary.app.data.reader.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.briefing.Briefing
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.briefing.BriefingStatus
import com.mavislibrary.app.data.briefing.BriefingStore
import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastRequest
import com.mavislibrary.app.data.podcast.PodcastService
import com.mavislibrary.app.data.podsub.PodsubRepository
import com.mavislibrary.app.data.podsub.SiteRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId

data class SitesUiState(
    val sources: List<FeedSource> = emptyList(),
    /** Latest posts per site id; absent while a site has not loaded yet. */
    val posts: Map<String, List<FeedStory>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val addOpen: Boolean = false,
    val siteInput: String = "",
    val findingFeed: Boolean = false,
    val feedResult: DiscoveryResult? = null,
    val preview: SitePreview? = null,
    val error: String? = null,
    val briefing: BriefingSettings = BriefingSettings(),
    val status: BriefingStatus? = null,
    val pending: Int = 0,
    val selectedSiteId: String? = null,
    val selectedArticles: Set<String> = emptySet(),
    val inFlightRequests: Set<String> = emptySet(),
    val queuedStatus: Map<String, String> = emptyMap(),
    val siteRules: Map<String, SiteRule> = emptyMap(),
    /** Read article ids per site id. */
    val read: Map<String, Set<String>> = emptyMap(),
    /** Today's ready subscription episodes for the Up next strip. */
    val upNext: List<PodcastJob> = emptyList(),
) {
    /** Unread stories across enabled followed sites, newest first. */
    fun allUnread(): List<Pair<FeedSource, FeedStory>> = sources.filter { it.enabled }.flatMap { s ->
        posts[s.id].orEmpty().unread(read[s.id].orEmpty()).map { s to it }
    }.distinctBy { it.second.item.url }.sortedByDescending { it.second.published ?: Long.MIN_VALUE }

    fun newCount(source: FeedSource) = posts[source.id].orEmpty().count { (it.published ?: 0) > source.seenSince }
    fun isRead(siteId: String, story: FeedStory) = story.item.id in read[siteId].orEmpty()
    fun unreadCount(source: FeedSource) = posts[source.id].orEmpty().unread(read[source.id].orEmpty()).size
}

/** Followed sites, their latest posts, and magazine shelf actions. [reschedule] applies a changed briefing schedule. */
class SitesViewModel(
    private val store: FeedStore,
    private val discovery: FeedDiscovery,
    private val feeds: FeedRepository,
    private val briefing: BriefingStore,
    private val podsub: PodsubRepository? = null,
    private val podcastApi: PodcastService? = null,
    private val reader: ReaderService? = null,
    private val readState: ReadStateStore? = null,
    private val reschedule: suspend () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SitesUiState())
    val state: StateFlow<SitesUiState> = _state.asStateFlow()
    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            store.sources.collect { sources ->
                val ids = sources.map { it.id }.toSet()
                _state.update { s -> s.copy(sources = sources, posts = s.posts.filterKeys { it in ids }, notes = s.notes.filterKeys { it in ids }) }
                sources.filter { it.enabled && it.id !in _state.value.posts && it.id !in _state.value.loading }.forEach(::load)
            }
        }
        readState?.let { r -> viewModelScope.launch { r.read.collect { read -> _state.update { it.copy(read = read) } } } }
        viewModelScope.launch { briefing.settings.collect { b -> _state.update { it.copy(briefing = b) } } }
        viewModelScope.launch { briefing.status.collect { b -> _state.update { it.copy(status = b) } } }
        viewModelScope.launch { briefing.pending.collect { p -> _state.update { it.copy(pending = p.size) } } }
        val p = podsub
        if (p != null) {
            viewModelScope.launch {
                runCatching {
                    val res = p.load()
                    _state.update { it.copy(siteRules = res.sites.associateBy { r -> r.id }) }
                }
            }
            viewModelScope.launch { runCatching { p.today() }.onSuccess { jobs -> _state.update { it.copy(upNext = jobs.filter { j -> j.status == "ready" }) } } }
        }
    }

    fun refresh() = _state.value.sources.filter { it.enabled && it.id !in _state.value.loading }.forEach(::load)

    fun load(source: FeedSource) {
        _state.update { it.copy(loading = it.loading + source.id) }
        viewModelScope.launch {
            val result = try { feeds.load(listOf(source)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FeedLoad(emptyList(), e.message ?: "Couldn't load ${source.name}.")
            }
            _state.update { s ->
                s.copy(posts = s.posts + (source.id to result.stories), loading = s.loading - source.id,
                    notes = if (result.note != null) s.notes + (source.id to result.note) else s.notes - source.id)
            }
        }
    }

    fun selectSite(id: String?) {
        _state.update { it.copy(selectedSiteId = id, selectedArticles = emptySet()) }
        if (id != null) {
            act { store.markViewed(id) }
            _state.value.sources.find { it.id == id }?.let { if (it.id !in _state.value.posts) load(it) }
        }
    }

    fun toggleArticleSelection(storyId: String) {
        _state.update { s ->
            s.copy(selectedArticles = if (storyId in s.selectedArticles) s.selectedArticles - storyId else s.selectedArticles + storyId)
        }
    }

    fun selectAllArticles(siteId: String) {
        val ids = _state.value.posts[siteId].orEmpty().map { it.item.id }.toSet()
        _state.update { it.copy(selectedArticles = ids) }
    }

    fun clearArticleSelection() {
        _state.update { it.copy(selectedArticles = emptySet()) }
    }

    fun setTopCount(id: String, count: Int) = act {
        store.setTopCount(id, count)
        if (count == 0) {
            store.setPodcast(id, SitePodcast.OFF)
        } else {
            val src = _state.value.sources.find { it.id == id }
            if (src?.podcast == SitePodcast.OFF) store.setPodcast(id, SitePodcast.BRIEFING)
        }
        val p = podsub
        if (p != null) {
            val doc = runCatching { p.load().document() }.getOrNull()
            if (doc != null) {
                val updated = doc.copy(sites = doc.sites.map { site ->
                    if (site.id == id) {
                        if (count == 0) site.copy(mode = "off", count = null)
                        else site.copy(mode = "top", count = count)
                    } else site
                })
                val res = p.save(updated)
                _state.update { it.copy(siteRules = res.sites.associateBy { r -> r.id }) }
            }
        }
    }

    fun setDelivery(id: String, delivery: String) = act {
        if (delivery == "separate") store.setPodcast(id, SitePodcast.OWN)
        else store.setPodcast(id, SitePodcast.BRIEFING)
        val p = podsub
        if (p != null) {
            val doc = runCatching { p.load().document() }.getOrNull()
            if (doc != null) {
                val updated = doc.copy(sites = doc.sites.map { if (it.id == id) it.copy(delivery = delivery) else it })
                val res = p.save(updated)
                _state.update { it.copy(siteRules = res.sites.associateBy { r -> r.id }) }
            }
        }
    }

    fun makeArticlePodcast(source: FeedSource, story: FeedStory) {
        val key = story.item.id
        if (key in _state.value.inFlightRequests) return
        _state.update { it.copy(inFlightRequests = it.inFlightRequests + key) }
        viewModelScope.launch {
            try {
                val req = if (story.item.url.startsWith("https://")) {
                    PodcastRequest(kind = "article", title = story.item.title.take(300), url = story.item.url)
                } else {
                    val fallbackText = story.body.ifBlank { story.item.summary }.ifBlank { story.item.title }
                    PodcastRequest(kind = "chapter", title = story.item.title.take(300), text = fallbackText)
                }
                val created = podcastApi?.create(req)
                val (pos, eta) = resolveQueueEta(created?.id, created?.queuePosition, created?.etaMin)
                val feedback = "Queued #$pos (~$eta min)"
                _state.update { it.copy(queuedStatus = it.queuedStatus + (key to feedback), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't create podcast. Try again.") }
            } finally {
                _state.update { it.copy(inFlightRequests = it.inFlightRequests - key) }
            }
        }
    }

    fun makeCombinedPodcast(source: FeedSource, stories: List<FeedStory>) {
        val key = "combined_${source.id}"
        if (stories.isEmpty() || key in _state.value.inFlightRequests) return
        _state.update { it.copy(inFlightRequests = it.inFlightRequests + key) }
        viewModelScope.launch {
            try {
                val heading = "Selected stories from ${source.name}."
                val text = Briefing.text(heading, stories.map { source to it }, ZoneId.systemDefault())
                val title = "${source.name} · ${stories.size} stories".take(300)
                val req = PodcastRequest(kind = "chapter", title = title, text = text)
                val created = podcastApi?.create(req)
                val (pos, eta) = resolveQueueEta(created?.id, created?.queuePosition, created?.etaMin)
                val feedback = "Queued #$pos (~$eta min)"
                _state.update {
                    it.copy(
                        queuedStatus = it.queuedStatus + (key to feedback),
                        selectedArticles = emptySet(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't create podcast. Try again.") }
            } finally {
                _state.update { it.copy(inFlightRequests = it.inFlightRequests - key) }
            }
        }
    }

    private suspend fun resolveQueueEta(createdId: String?, givenPos: Int?, givenEta: Int?): Pair<Int, Int> {
        if (givenPos != null) return givenPos to (givenEta ?: (givenPos * 5))
        val jobs = runCatching { podcastApi?.list() }.getOrNull().orEmpty()
        val queued = jobs.filter { it.status in setOf("queued", "sourcing", "writing", "generating") }
        val idx = if (createdId != null) queued.indexOfFirst { it.id == createdId } else -1
        val pos = if (idx >= 0) (queued[idx].queuePosition ?: (idx + 1)) else maxOf(1, queued.size)
        val eta = (if (idx >= 0) queued[idx].etaMin else null) ?: (pos * 5)
        return pos to eta
    }

    fun openAddSite() = _state.update { it.copy(addOpen = true, error = null) }
    fun closeAddSite() {
        discoveryJob?.cancel()
        _state.update { it.copy(addOpen = false, findingFeed = false, feedResult = null, preview = null, siteInput = "", error = null) }
    }
    fun siteInput(value: String) {
        discoveryJob?.cancel()
        _state.update { it.copy(siteInput = value, findingFeed = false, feedResult = null, preview = null, error = null) }
    }
    fun findFeed() {
        discoveryJob?.cancel()
        val input = _state.value.siteInput
        _state.update { it.copy(findingFeed = true, feedResult = null, preview = null, error = null) }
        discoveryJob = viewModelScope.launch {
            val result = discoverSite(input, discovery, reader)
            _state.update { it.copy(findingFeed = false, feedResult = result.result, preview = result.preview) }
        }
    }
    fun addFeed() {
        val found = _state.value.feedResult as? DiscoveryResult.Found ?: return
        val source = _state.value.preview?.source ?: "feed"
        act { store.add(found, source); closeAddSite() }
    }
    fun markAllRead(siteId: String) = act {
        readState?.markRead(siteId, _state.value.posts[siteId].orEmpty().map { it.item.id }.reversed())
    }
    fun rename(id: String, name: String) = act { store.rename(id, name) }
    fun setEnabled(id: String, enabled: Boolean) = act { store.setEnabled(id, enabled) }
    fun remove(id: String) = act {
        store.remove(id)
        val p = podsub
        if (p != null) {
            val doc = runCatching { p.load().document() }.getOrNull()
            if (doc != null) {
                val updated = doc.copy(sites = doc.sites.filterNot { it.id == id })
                runCatching {
                    val res = p.save(updated)
                    _state.update { it.copy(siteRules = res.sites.associateBy { r -> r.id }) }
                }
            }
        }
    }
    fun setPodcast(id: String, mode: String) = act { store.setPodcast(id, mode) }
    fun setBriefing(enabled: Boolean) = act { briefing.setEnabled(enabled); reschedule() }
    fun setBriefingTime(time: LocalTime) = act { briefing.setTime(time); reschedule() }

    private fun act(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); _state.update { it.copy(error = null) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Couldn't save that change.") } }
        }
    }
}
