package com.mavislibrary.app.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import com.mavislibrary.app.data.kids.kidsPrefs
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mavislibrary.app.ui.common.Cover
import com.mavislibrary.app.ui.common.BookTile
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.ui.theme.LocalLookTokens

private val SUGGESTIONS = listOf("Jane Austen", "Sherlock Holmes", "Psalms", "Fairy tales", "Dickens", "Poetry")

@Composable
fun SearchScreen(container: AppContainer, online: Boolean, onOpenBook: (com.mavislibrary.app.data.model.Book) -> Unit = {}, onScan: (() -> Unit)? = null) {
    val appContext = LocalContext.current.applicationContext
    val kidsPrefs = remember(appContext) { appContext.kidsPrefs() }
    val kids by kidsPrefs.state.collectAsStateWithLifecycle(initialValue = null)
    if (!techSitesAllowed(kids?.enabled)) return
    val vm: SearchViewModel = viewModel(factory = viewModelFactory {
        initializer { SearchViewModel(container.searchRepository, container.shelfRepository, container.feedStore, container.feedDiscovery, container.feedRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val shelf by vm.shelfKeys.collectAsStateWithLifecycle()
    SearchContent(state, shelf, online, vm::onQueryChange, vm::onSourceChange, vm::submit, vm::loadMore, vm::select, vm::loadDiscover, vm::loadTech, onScan, { MyTechSites(state, vm) })
    state.selected?.let { book ->
        BookDetailSheet(book, onShelf = book.key in shelf, onToggleShelf = { vm.toggleShelf(book) }, onOpen = { vm.select(null); onOpenBook(book) }, onDismiss = { vm.select(null) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchContent(
    state: SearchUiState,
    shelf: Set<String>,
    online: Boolean,
    onQuery: (String) -> Unit,
    onSource: (BookSource) -> Unit,
    onSubmit: (String) -> Unit,
    onMore: () -> Unit,
    onSelect: (Book) -> Unit,
    onRetryDiscover: () -> Unit = {},
    onTech: (String) -> Unit = {},
    onScan: (() -> Unit)? = null,
    customSites: @Composable () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tokens = LocalLookTokens.current
    val focus = LocalFocusManager.current
    val submit = { q: String -> focus.clearFocus(); onSubmit(q) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(tokens.coverMinWidth),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.gridSpacing),
        modifier = Modifier.fillMaxSize().imePadding().testTag("search_grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Find a book", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (onScan != null) OutlinedButton(onClick = onScan, modifier = Modifier.testTag("search_scan")) { Text("Scan") }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth().testTag("search_field"),
                    singleLine = true,
                    placeholder = { Text("Title, author or subject") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BookSource.entries.forEachIndexed { i, src ->
                        SegmentedButton(
                            selected = state.source == src,
                            onClick = { onSource(src) },
                            shape = SegmentedButtonDefaults.itemShape(i, BookSource.entries.size),
                        ) { Text(src.label) }
                    }
                }
                Text(
                    if (state.source == BookSource.GUTENBERG) "Public-domain books from Project Gutenberg, free to read."
                    else "Any book, from Open Library. Finding a book here does not mean it is free.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.savedAt?.let { at ->
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            "Saved results from ${DateUtils.getRelativeTimeSpanString(at)}. They may be out of date.",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("saved_results_note"),
                        )
                    }
                }
                if (state.submittedQuery == null) {
                    Text("Try", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { onQuery(s); submit(s) }, label = { Text(s) }) }
                    }
                }
            }
        }

        if (state.submittedQuery == null && state.source == BookSource.GUTENBERG) {
            if (state.discover.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Centered {
                        if (state.discoverError != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.discoverError, textAlign = TextAlign.Center, modifier = Modifier.testTag("discover_error"))
                            if (online) OutlinedButton(onClick = onRetryDiscover) { Text("Try again") }
                        } else CircularProgressIndicator()
                    }
                }
            }
            state.discover.forEach { shelfRow ->
                item(key = "shelf-head-${shelfRow.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(shelfRow.label, style = MaterialTheme.typography.titleLarge)
                        if (shelfRow.blurb.isNotBlank()) Text(shelfRow.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "shelf-row-${shelfRow.id}", span = { GridItemSpan(maxLineSpan) }) {
                    val books = shelfRow.books
                    if (books == null) Centered { CircularProgressIndicator() }
                    else if (books.isEmpty()) Text("Couldn't load this shelf right now.", style = MaterialTheme.typography.bodySmall)
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(tokens.gridSpacing), modifier = Modifier.testTag("discover_row_${shelfRow.id}")) {
                        rowItems(books, key = { it.key }) { book ->
                            Box(Modifier.width(tokens.coverMinWidth)) { BookTile(book, onShelf = book.key in shelf, onClick = { onSelect(book) }) }
                        }
                    }
                }
            }
        }

        if (state.submittedQuery == null && state.source == BookSource.GUTENBERG) {
            item(key = "tech-head", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New in tech & AI", style = MaterialTheme.typography.titleLarge)
                    customSites()
                    Text("Live from Hacker News and arXiv. Opens the original article.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ai" to "AI news", "papers" to "Research papers", "tech" to "Tech front page").forEach { (kind, label) ->
                            FilterChip(selected = state.techKind == kind, onClick = { onTech(kind) }, label = { Text(label) })
                        }
                    }
                }
            }
            state.techNote?.let { note ->
                item(key = "tech-note", span = { GridItemSpan(maxLineSpan) }) { Text(note, style = MaterialTheme.typography.bodySmall) }
            }
            val techItems = state.tech
            if (techItems == null) item(key = "tech-loading", span = { GridItemSpan(maxLineSpan) }) { Centered { CircularProgressIndicator() } }
            else if (techItems.isEmpty()) item(key = "tech-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(state.techError ?: "Nothing new right now.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tech_empty"))
            }
            else techItems.forEach { item ->
                item(key = "tech-${state.techKind}-${item.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: ActivityNotFoundException) { }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("tech_item"),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(item.source.takeIf { it.isNotBlank() }, item.points.takeIf { it > 0 }?.let { "▲ $it" }, item.comments.takeIf { it > 0 }?.let { "$it comments" }).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        when {
            state.loading -> item(span = { GridItemSpan(maxLineSpan) }) { Centered { CircularProgressIndicator() } }
            state.error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("search_error"))
                        if (online) OutlinedButton(onClick = { submit(state.submittedQuery ?: state.query) }) { Text("Try again") }
                    }
                }
            }
            state.submittedQuery != null && state.books.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Centered { Text("No books matched \u201c${state.submittedQuery}\u201d.", style = MaterialTheme.typography.bodyLarge) }
            }
        }

        items(state.books, key = { it.key }) { book ->
            BookTile(book, onShelf = book.key in shelf, onClick = { onSelect(book) })
        }

        if (state.hasNext && !state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Centered {
                    if (state.loadingMore) CircularProgressIndicator() else FilledTonalButton(onClick = onMore) { Text("Show more") }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookDetailSheet(book: Book, onShelf: Boolean, onToggleShelf: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("book_detail"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(book, Modifier.width(120.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    Text(book.authorLine, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    book.year?.let { Text("First published $it", style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        if (book.source == BookSource.GUTENBERG) "Project Gutenberg" else "Open Library",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Formats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                book.formats.joinToString("  \u2022  ") { f -> if (f.free) "${f.label} \u00b7 free" else f.label }.ifBlank { "No downloadable file listed" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (book.source == BookSource.OPEN_LIBRARY) {
                Text("Open Library is for discovery. A listing here does not mean the book is free or available.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("open_book_button")) {
                Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open book page")
            }
            OutlinedButton(onClick = onToggleShelf, modifier = Modifier.fillMaxWidth().testTag("shelf_button")) {
                Icon(if (onShelf) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (onShelf) "On your shelf \u00b7 Remove" else "Add to shelf")
            }
            book.sourceUrl?.let { url ->
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) { }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (book.source == BookSource.GUTENBERG) "View on Project Gutenberg" else "View on Open Library")
                }
            }
        }
    }
}

internal fun techSitesAllowed(kidsEnabled: Boolean?): Boolean = kidsEnabled != null && com.mavislibrary.app.data.kids.adultRoutesAllowed(kidsEnabled)
