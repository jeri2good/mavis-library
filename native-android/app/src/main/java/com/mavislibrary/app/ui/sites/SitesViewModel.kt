package com.mavislibrary.app.ui.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.briefing.BriefingStatus
import com.mavislibrary.app.data.briefing.BriefingStore
import com.mavislibrary.app.data.feeds.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

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
    val error: String? = null,
    val briefing: BriefingSettings = BriefingSettings(),
    val status: BriefingStatus? = null,
    val pending: Int = 0,
) {
    fun newCount(source: FeedSource) = posts[source.id].orEmpty().count { (it.published ?: 0) > source.seenSince }
}

/** Followed sites, their latest posts, and the daily podcast switch. [reschedule] applies a changed briefing schedule. */
class SitesViewModel(
    private val store: FeedStore,
    private val discovery: FeedDiscovery,
    private val feeds: FeedRepository,
    private val briefing: BriefingStore,
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
        viewModelScope.launch { briefing.settings.collect { b -> _state.update { it.copy(briefing = b) } } }
        viewModelScope.launch { briefing.status.collect { b -> _state.update { it.copy(status = b) } } }
        viewModelScope.launch { briefing.pending.collect { p -> _state.update { it.copy(pending = p.size) } } }
    }

    fun refresh() = _state.value.sources.filter { it.enabled && it.id !in _state.value.loading }.forEach(::load)

    private fun load(source: FeedSource) {
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

    fun openAddSite() = _state.update { it.copy(addOpen = true, error = null) }
    fun closeAddSite() {
        discoveryJob?.cancel()
        _state.update { it.copy(addOpen = false, findingFeed = false, feedResult = null, siteInput = "", error = null) }
    }
    fun siteInput(value: String) {
        discoveryJob?.cancel()
        _state.update { it.copy(siteInput = value, findingFeed = false, feedResult = null, error = null) }
    }
    fun findFeed() {
        discoveryJob?.cancel()
        val input = _state.value.siteInput
        _state.update { it.copy(findingFeed = true, feedResult = null, error = null) }
        discoveryJob = viewModelScope.launch {
            val result = discovery.discover(input)
            _state.update { it.copy(findingFeed = false, feedResult = result) }
        }
    }
    fun addFeed() {
        val found = _state.value.feedResult as? DiscoveryResult.Found ?: return
        act { store.add(found); closeAddSite() }
    }
    fun rename(id: String, name: String) = act { store.rename(id, name) }
    fun setEnabled(id: String, enabled: Boolean) = act { store.setEnabled(id, enabled) }
    fun remove(id: String) = act { store.remove(id) }
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
