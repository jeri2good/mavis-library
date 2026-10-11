package com.mavislibrary.app.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.repo.CuratedCatalog
import com.mavislibrary.app.data.repo.SavedShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
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
)

class ShelfViewModel(private val shelf: SavedShelf, private val search: CuratedCatalog) : ViewModel() {
    private val _state = MutableStateFlow(ShelfUiState())
    val state = _state.asStateFlow()
    private var popularJob: Job? = null
    private var popularRequested = false

    init {
        viewModelScope.launch {
            try {
                shelf.shelfBooks.collect { books ->
                    _state.update { it.copy(books = books, loading = false) }
                    if (books.isEmpty() && !popularRequested) loadPopular()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = "Couldn't load your saved books.") }
                loadPopular()
            }
        }
    }

    fun loadPopular() {
        if (popularJob?.isActive == true) return
        popularRequested = true
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
