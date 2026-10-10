package com.mavislibrary.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.repo.Origin
import com.mavislibrary.app.data.repo.SearchRepository
import com.mavislibrary.app.data.repo.ShelfRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
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
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val shelfKeys: StateFlow<Set<String>> = shelf.shelfKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var job: Job? = null

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
