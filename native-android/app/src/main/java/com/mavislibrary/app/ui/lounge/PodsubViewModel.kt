package com.mavislibrary.app.ui.lounge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podsub.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodsubUiState(val document: SubscriptionDocument? = null, val usage: DailyUsage? = null,
    val episodes: List<PodcastJob> = emptyList(), val lastRun: SubscriptionRun? = null, val nextRunAt: String? = null,
    val busy: Boolean = false, val dirty: Boolean = false, val error: String? = null) {
    val meter get() = usage?.let { "Today: ${it.used} of ${it.limit} used" } ?: "Today: usage unavailable"
}
class PodsubViewModel(private val repository: PodsubRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PodsubUiState())
    val state = mutable.asStateFlow()
    fun edit(change: (SubscriptionDocument) -> SubscriptionDocument) {
        if (state.value.busy) return
        mutable.update { it.document?.let { doc -> it.copy(document = change(doc), dirty = true) } ?: it }
    }
    fun move(id: String, delta: Int) = edit { doc ->
        val sites = doc.sites.sortedBy { it.priority }.toMutableList()
        val from = sites.indexOfFirst { it.id == id }; val to = from + delta
        if (from in sites.indices && to in sites.indices) { val site = sites.removeAt(from); sites.add(to, site) }
        doc.copy(sites = sites.mapIndexed { i, site -> site.copy(priority = i) })
    }
    fun refresh() = act { refreshData() }
    private suspend fun refreshData() {
        val response = repository.load()
        mutable.update { it.copy(document = if (it.dirty) it.document else response.document(), usage = response.today,
            lastRun = response.lastRun, nextRunAt = response.nextRunAt) }
        val episodes = repository.today()
        mutable.update { it.copy(episodes = episodes) }
    }
    fun save() = act {
        val doc = state.value.document ?: return@act
        val response = repository.save(doc)
        mutable.update { it.copy(document = response.document(), dirty = false, nextRunAt = response.nextRunAt) }
        refreshData()
    }
    fun runNow() = act {
        check(!state.value.dirty) { "Save your changes before running." }
        val run = repository.runNow()
        mutable.update { it.copy(lastRun = run) }
        refreshData()
        mutable.update { it.copy(lastRun = run) }
    }
    private fun act(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Couldn't reach Podcast Studio. Try again.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
