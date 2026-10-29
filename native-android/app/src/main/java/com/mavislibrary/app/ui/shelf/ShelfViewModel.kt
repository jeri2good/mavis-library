package com.mavislibrary.app.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.repo.CuratedCatalog
import com.mavislibrary.app.data.repo.SavedShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PopularShelf(val id: String, val label: String, val blurb: String, val books: List<Book>? = null)

data class ShelfUiState(
    val books: List<Book> = emptyList(),
    val loading: Boolean = true,
    val popular: List<PopularShelf> = emptyList(),
    val popularLoading: Boolean = false,
    val popularError: String? = null,
    val error: String? = null,
    /** Last reading/listening time per book key. */
    val activity: Map<String, Long> = emptyMap(),
    val siteSources: List<FeedSource> = emptyList(),
    val sitePosts: Map<String, List<FeedStory>> = emptyMap(),
    val siteRead: Map<String, Set<String>> = emptyMap(),
) {
    val continueReading get() = continueReading(books, activity)
    val sites get() = siteBooks(siteSources, sitePosts, siteRead)
}

/** Followed sites for the Sites shelf. [stories] is the cached feed load the Sites tab uses; [mirror] pulls server briefing sites into FeedStore. */
interface ShelfSites {
    val sources: Flow<List<FeedSource>>
    val read: Flow<Map<String, Set<String>>>
    suspend fun stories(source: FeedSource): List<FeedStory>
    suspend fun mirror() = Unit
}

class ShelfViewModel(
    private val shelf: SavedShelf,
    private val search: CuratedCatalog,
    activity: Flow<Map<String, Long>> = flowOf(emptyMap()),
    private val sites: ShelfSites? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ShelfUiState())
    val state = _state.asStateFlow()
    private var popularJob: Job? = null
    private var mirrorJob: Job? = null
    private val loadingSites = mutableSetOf<String>()
    private var screenVisits = 0

    init {
        viewModelScope.launch {
            try {
                shelf.shelfBooks.collect { books ->
                    _state.update { it.copy(books = books, loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = "Couldn't load your saved books.") }
            }
        }
        loadPopular()
        viewModelScope.launch {
            try { activity.collect { a -> _state.update { it.copy(activity = a) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        sites?.let { s ->
            viewModelScope.launch {
                try { s.read.collect { read -> _state.update { it.copy(siteRead = read) } } }
                catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            viewModelScope.launch {
                try {
                    s.sources.collect { sources ->
                        val ids = sources.map { it.id }.toSet()
                        _state.update { st -> st.copy(siteSources = sources, sitePosts = st.sitePosts.filterKeys { it in ids }) }
                        loadSiteStories(sources, refresh = false)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            mirrorSites()
        }
    }

    /** Called when ShelfScreen enters composition or credentials/connectivity change; skips the initial call since init already synced. */
    fun onScreenShown() {
        if (screenVisits++ > 0) syncSites()
    }

    /** Mirrors server briefing sites into FeedStore and refreshes stories (cached in FeedRepository for 15 min, or retried if missing/failed). */
    fun syncSites() {
        if (sites == null) return
        mirrorSites()
        loadSiteStories(_state.value.siteSources, refresh = true)
    }

    private fun mirrorSites() {
        val s = sites ?: return
        if (mirrorJob?.isActive == true) return
        mirrorJob = viewModelScope.launch {
            try { s.mirror() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    private fun loadSiteStories(sources: List<FeedSource>, refresh: Boolean) {
        val s = sites ?: return
        sources.filter { it.enabled && it.id !in loadingSites && (refresh || it.id !in _state.value.sitePosts) }.forEach { source ->
            loadingSites += source.id
            viewModelScope.launch {
                try {
                    val stories = try { s.stories(source) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    _state.update { st ->
                        if (st.siteSources.any { it.id == source.id }) st.copy(sitePosts = st.sitePosts + (source.id to stories)) else st
                    }
                } finally {
                    loadingSites -= source.id
                }
            }
        }
    }

    fun loadPopular() {
        if (popularJob?.isActive == true) return
        popularJob = viewModelScope.launch {
            _state.update { it.copy(popularLoading = true, popularError = null) }
            try {
                val definitions = search.curatedShelves().value
                _state.update { it.copy(popular = definitions.map { d -> PopularShelf(d.id, d.label, d.blurb) }) }
                val gate = Semaphore(3)
                definitions.map { definition ->
                    async {
                        val books = try {
                            gate.withPermit { search.booksByIds(definition.ids).value }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            _state.update { it.copy(popularError = "Some popular picks couldn't load. Try again when you're online.") }
                            emptyList()
                        }
                        _state.update { s -> s.copy(popular = s.popular.map {
                            if (it.id == definition.id) it.copy(books = books) else it
                        }) }
                    }
                }.awaitAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(popularError = "Popular picks couldn't load. Try again when you're online.") }
            } finally {
                _state.update { it.copy(popularLoading = false) }
            }
        }
    }

    fun remove(book: Book) {
        viewModelScope.launch {
            try {
                shelf.remove(book.key)
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "Couldn't remove this book. Please try again.") }
            }
        }
    }
}
