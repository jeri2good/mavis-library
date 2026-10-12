package com.mavislibrary.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.TechItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.repo.Origin
import com.mavislibrary.app.data.repo.SearchRepository
import com.mavislibrary.app.data.repo.ShelfRepository
import com.mavislibrary.app.data.feeds.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A curated shelf on the Search start screen. [books] is null while it loads, empty if it failed. */
data class DiscoverShelf(val id: String, val label: String, val blurb: String, val books: List<Book>? = null)

data class SearchUiState(
    val sources: List<FeedSource> = emptyList(),
    val addSiteOpen: Boolean = false,
    val siteInput: String = "",
    val findingFeed: Boolean = false,
    val feedResult: DiscoveryResult? = null,
    val sourceError: String? = null,
    val techNote: String? = null,
    val techKind: String = "ai",
    val tech: List<TechItem>? = null,
    val techError: String? = null,
    val discover: List<DiscoverShelf> = emptyList(),
    val discoverError: String? = null,
    val query: String = "",
    val source: BookSource = BookSource.GUTENBERG,
    val submittedQuery: String? = null,
    val books: List<Book> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** Set when results came from the device cache because the network was unavailable. */
    val savedAt: Long? = null,
    val selected: Book? = null,
)

class SearchViewModel(
    private val search: SearchRepository,
    private val shelf: ShelfRepository,
    private val feedStore: FeedStore? = null,
    private val feedDiscovery: FeedDiscovery? = null,
    private val feedRepository: FeedRepository? = null,

) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val shelfKeys: StateFlow<Set<String>> = shelf.shelfKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var job: Job? = null

    private var discoveryJob: Job? = null
    private var techJob: Job? = null
    init {
        loadDiscover(); loadTech("ai")
        if (feedStore != null) viewModelScope.launch {
            feedStore.sources.collect { sources ->
                _state.update { it.copy(sources = sources) }
                val kind = _state.value.techKind
                if (kind == "mine" || kind.startsWith("site:")) {
                    loadTech(if (kind == "mine" || sources.any { "site:${it.id}" == kind && it.enabled }) kind else "mine")
                }
            }
        }
    }

    fun openAddSite() = _state.update { it.copy(addSiteOpen = true, sourceError = null) }
    fun closeAddSite() {
        discoveryJob?.cancel()
        _state.update { it.copy(addSiteOpen = false, findingFeed = false, feedResult = null, sourceError = null) }
    }
    fun siteInput(value: String) {
        discoveryJob?.cancel()
        _state.update { it.copy(siteInput = value, findingFeed = false, feedResult = null, sourceError = null) }
    }
    fun findFeed() {
        val discovery = feedDiscovery ?: return
        discoveryJob?.cancel()
        val input = _state.value.siteInput
        _state.update { it.copy(findingFeed = true, feedResult = null, sourceError = null) }
        discoveryJob = viewModelScope.launch {
            val result = discovery.discover(input)
            _state.update { it.copy(findingFeed = false, feedResult = result) }
        }
    }
    fun addFeed() {
        val found = _state.value.feedResult as? DiscoveryResult.Found ?: return
        sourceAction {
            val source = feedStore?.add(found) ?: return@sourceAction
            closeAddSite()
            // Read the added source immediately: the DataStore collector may resume afterward.
            _state.update { it.copy(sources = (it.sources.filterNot { s -> s.id == source.id } + source)) }
            loadTech("site:${source.id}")
        }
    }
    fun renameFeed(id: String, name: String) = sourceAction { feedStore?.rename(id, name) }
    fun enableFeed(id: String, enabled: Boolean) = sourceAction { feedStore?.setEnabled(id, enabled) }
    fun removeFeed(id: String) = sourceAction { feedStore?.remove(id) }
    private fun sourceAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); _state.update { it.copy(sourceError = null) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(sourceError = e.message ?: "Couldn't save this site.") } }
        }
    }

    fun loadTech(kind: String) {
        techJob?.cancel()
        _state.update { it.copy(techKind = kind, tech = null, techError = null, techNote = null) }
        techJob = viewModelScope.launch {
            if (kind == "mine" || kind.startsWith("site:")) {
                val sources = _state.value.sources.filter { it.enabled && (kind == "mine" || kind == "site:${it.id}") }
                val result = feedRepository?.load(sources) ?: FeedLoad(emptyList())
                _state.update { if (it.techKind == kind) it.copy(tech = result.stories.map { s -> s.item }, techNote = result.note) else it }
                return@launch
            }
            if (kind !in setOf("ai", "papers", "tech")) return@launch
            try {
                val items = search.techFeed(kind).value.take(12)
                _state.update { if (it.techKind == kind) it.copy(tech = items) else it }
            } catch (e: ApiException) {
                _state.update { if (it.techKind == kind) it.copy(tech = emptyList(), techError = e.message) else it }
            }
        }
    }

    /** Fills the start screen with the curated shelves (popular, children's, science, tinker, ...). */
    fun loadDiscover() {
        viewModelScope.launch {
            try {
                val defs = search.curatedShelves().value
                _state.update { s -> s.copy(discoverError = null, discover = defs.map { DiscoverShelf(it.id, it.label, it.blurb) }) }
                val gate = Semaphore(3)
                defs.map { def ->
                    async {
                        val books = gate.withPermit { runCatching { search.booksByIds(def.ids).value }.getOrDefault(emptyList()) }
                        _state.update { s -> s.copy(discover = s.discover.map { if (it.id == def.id) it.copy(books = books) else it }) }
                    }
                }.awaitAll()
            } catch (e: ApiException) {
                _state.update { it.copy(discoverError = e.message) }
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun onSourceChange(source: BookSource) {
        if (source == _state.value.source) return
        _state.update { it.copy(source = source) }
        if (_state.value.submittedQuery != null) submit()
    }

    fun submit(query: String = _state.value.query) {
        val q = query.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(query = q, submittedQuery = q, loading = true, error = null, books = emptyList(), page = 0, hasNext = false, savedAt = null) }
        load(q, page = 1, append = false)
    }

    fun loadMore() {
        val s = _state.value
        val q = s.submittedQuery ?: return
        if (!s.hasNext || s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        load(q, page = s.page + 1, append = true)
    }

    private fun load(q: String, page: Int, append: Boolean) {
        job?.cancel()
        val source = _state.value.source
        job = viewModelScope.launch {
            try {
                val res = search.search(source, q, page)
                _state.update { s ->
                    val merged = if (append) (s.books + res.value.books).distinctBy { it.key } else res.value.books
                    s.copy(
                        books = merged, page = res.value.page, hasNext = res.value.hasNext, loading = false, loadingMore = false,
                        savedAt = if (res.origin == Origin.CACHE_STALE) res.fetchedAt else null,
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, loadingMore = false, error = e.message) }
            }
        }
    }

    fun select(book: Book?) = _state.update { it.copy(selected = book) }

    fun toggleShelf(book: Book) {
        viewModelScope.launch {
            if (book.key in shelfKeys.value) shelf.remove(book.key) else shelf.add(book)
        }
    }
}
