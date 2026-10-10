package com.mavislibrary.app.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
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
import coil3.compose.SubcomposeAsyncImage
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.ui.theme.LocalLookTokens

private val SUGGESTIONS = listOf("Jane Austen", "Sherlock Holmes", "Psalms", "Fairy tales", "Dickens", "Poetry")

@Composable
fun SearchScreen(container: AppContainer, online: Boolean) {
    val vm: SearchViewModel = viewModel(factory = viewModelFactory {
        initializer { SearchViewModel(container.searchRepository, container.shelfRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val shelf by vm.shelfKeys.collectAsStateWithLifecycle()
    SearchContent(state, shelf, online, vm::onQueryChange, vm::onSourceChange, vm::submit, vm::loadMore, vm::select)
    state.selected?.let { book ->
        BookDetailSheet(book, onShelf = book.key in shelf, onToggleShelf = { vm.toggleShelf(book) }, onDismiss = { vm.select(null) })
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
) {
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
                Text("Find a book", style = MaterialTheme.typography.headlineMedium)
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

@Composable
fun Cover(book: Book, modifier: Modifier = Modifier) {
    val placeholder = @Composable {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
    Box(modifier.aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)) {
        if (book.coverUrl == null) placeholder()
        else SubcomposeAsyncImage(
            model = book.coverUrl,
            contentDescription = "Cover of ${book.title}",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            loading = { placeholder() },
            error = { placeholder() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BookTile(book: Book, onShelf: Boolean, onClick: () -> Unit) {
    val tokens = LocalLookTokens.current
    Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).testTag("book_tile")) {
        Box {
            Cover(book, Modifier.fillMaxWidth())
            if (onShelf) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Icon(Icons.Outlined.BookmarkAdded, contentDescription = "On your shelf", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(4.dp).size(18.dp))
                }
            }
        }
        if (tokens.showCoverCaptions) {
            Spacer(Modifier.height(6.dp))
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookDetailSheet(book: Book, onShelf: Boolean, onToggleShelf: () -> Unit, onDismiss: () -> Unit) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                book.formats.forEach { f -> AssistChip(onClick = {}, label = { Text(if (f.free) "${f.label} \u00b7 free" else f.label) }) }
            }
            if (book.source == BookSource.OPEN_LIBRARY) {
                Text("Open Library is for discovery. A listing here does not mean the book is free or available.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onToggleShelf, modifier = Modifier.fillMaxWidth().testTag("shelf_button")) {
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
