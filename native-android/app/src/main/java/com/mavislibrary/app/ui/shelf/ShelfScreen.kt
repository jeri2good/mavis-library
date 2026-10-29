package com.mavislibrary.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.stats.statsRepository
import kotlinx.coroutines.flow.combine

@Composable
fun ShelfScreen(
    container: AppContainer,
    onOpenBook: (Book) -> Unit,
    onOpenWebShelf: () -> Unit,
    onScan: (() -> Unit)? = null,
    onPodcast: ((Book) -> Unit)? = null,
    onOpenSite: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val vm: ShelfViewModel = viewModel(factory = viewModelFactory {
        initializer {
            val activity = combine(context.statsRepository().state, container.resumePositionStore.bookActivity()) { stats, listened ->
                readingActivity(stats, listened)
            }
            val sites = object : ShelfSites {
                override val sources = container.feedStore.sources
                override val read = container.readState.read
                override suspend fun stories(source: FeedSource) = container.feedRepository.load(listOf(source)).stories
            }
            ShelfViewModel(container.shelfRepository, container.searchRepository, activity, sites)
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    ShelfContent(state, onOpenBook, onOpenWebShelf, onScan, onPodcast, onOpenSite, onRemove = vm::remove, onRetryPopular = vm::loadPopular)
}

@Composable
internal fun ShelfContent(
    state: ShelfUiState,
    onOpenBook: (Book) -> Unit,
    onOpenWebShelf: () -> Unit,
    onScan: (() -> Unit)?,
    onPodcast: ((Book) -> Unit)?,
    onOpenSite: ((String) -> Unit)?,
    onRemove: (Book) -> Unit,
    onRetryPopular: () -> Unit,
) {
    val rows = shelfRows(state)
    LazyColumn(Modifier.fillMaxSize().testTag("shelf_grid"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                Text("Your shelf", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onOpenWebShelf, modifier = Modifier.testTag("open_web_shelf"), contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Open web shelf (collections, import, quotes)", style = MaterialTheme.typography.labelMedium)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        rows.forEach { row ->
            if (row is ShelfRow.Popular && row === rows.firstOrNull { it is ShelfRow.Popular }) popularStatus(state, onRetryPopular)
            item(key = "row-${row.id}", contentType = "shelf") {
                Column(Modifier.fillMaxWidth().testTag("shelf_row_${row.id}")) {
                    ShelfBay { RowContents(row, onOpenBook, onScan, onPodcast, onOpenSite, onRemove) }
                    ShelfPlank(row.id, row.label)
                }
            }
        }
        if (rows.none { it is ShelfRow.Popular }) popularStatus(state, onRetryPopular)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.popularStatus(state: ShelfUiState, onRetry: () -> Unit) {
    val starting = state.popularLoading && state.popular.isEmpty()
    val empty = !state.popularLoading && state.popular.isEmpty() && state.popularError == null
    if (!starting && !empty && state.popularError == null) return
    item(key = "popular-status") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (starting) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.popularError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry, enabled = !state.popularLoading) { Text("Try again") }
            }
            if (empty) Text("No popular picks are available right now. Find your next read in Search.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BoxScope.RowContents(
    row: ShelfRow, onOpenBook: (Book) -> Unit, onScan: (() -> Unit)?, onPodcast: ((Book) -> Unit)?,
    onOpenSite: ((String) -> Unit)?, onRemove: (Book) -> Unit,
) {
    when (row) {
        is ShelfRow.Mine -> when {
            row.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WallText)
            row.books.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WallMessage("Save books from Search to see them here")
                if (onScan != null) OutlinedButton(onClick = onScan, modifier = Modifier.testTag("shelf_scan"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WallText)) { Text("Scan a book barcode") }
            }
            else -> LoopingRow(row.books, { it.key }) { book -> SavedBook(book, onOpenBook, onPodcast, onRemove) }
        }
        is ShelfRow.Continue -> LoopingRow(row.books, { it.key }) { book -> ShelfBook(book, onClick = { onOpenBook(book) }) }
        is ShelfRow.Popular -> {
            val books = row.shelf.books
            when {
                books == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = WallText)
                books.isEmpty() -> WallMessage("No books available in this row right now.", Modifier.align(Alignment.Center).padding(horizontal = 32.dp))
                else -> LoopingRow(books, { it.key }) { book -> ShelfBook(book, onClick = { onOpenBook(book) }) }
            }
        }
        is ShelfRow.Sites -> LoopingRow(row.sites, { it.id }) { site -> SiteShelfBook(site, onClick = { onOpenSite?.invoke(site.id) }) }
    }
}

@Composable
private fun SavedBook(book: Book, onOpenBook: (Book) -> Unit, onPodcast: ((Book) -> Unit)?, onRemove: (Book) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ShelfBook(book, onClick = { onOpenBook(book) }, onLongClick = { menuOpen = true })
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onPodcast != null) DropdownMenuItem(text = { Text("Make a podcast") }, onClick = { menuOpen = false; onPodcast(book) })
            DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = { menuOpen = false; onRemove(book) })
        }
    }
}

/** Loops forever in both directions when there are more items than fit; otherwise a plain left-aligned row. */
@Composable
private fun <T> LoopingRow(items: List<T>, key: (T) -> String, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = SHELF_EDGE + 8.dp
        val fits = with(LocalDensity.current) { CircularRow.fits((maxWidth - padding * 2).toPx(), BOOK_WIDTH.toPx(), BOOK_SPACING.toPx()) }
        val loops = CircularRow.loops(items.size, fits)
        val count = items.size
        val listState = remember(count, loops) { LazyListState(CircularRow.startIndex(count, loops)) }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(BOOK_SPACING),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(CircularRow.itemCount(count, loops), key = { CircularRow.key(it, key(items[CircularRow.realIndex(it, count)])) }, contentType = { "book" }) {
                content(items[CircularRow.realIndex(it, count)])
            }
        }
    }
}
