package com.mavislibrary.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.ui.common.BookTile
import com.mavislibrary.app.ui.theme.LocalLookTokens

@Composable
fun ShelfScreen(container: AppContainer, onOpenBook: (com.mavislibrary.app.data.model.Book) -> Unit, onOpenWebShelf: () -> Unit) {
    val vm: ShelfViewModel = viewModel(factory = viewModelFactory {
        initializer { ShelfViewModel(container.shelfRepository, container.searchRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val tokens = LocalLookTokens.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tokens.coverMinWidth),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.gridSpacing),
        modifier = Modifier.fillMaxSize().testTag("shelf_grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your shelf", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onOpenWebShelf, modifier = Modifier.testTag("open_web_shelf")) {
                    Text("Open web shelf (collections, import, quotes)")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) { CircularProgressIndicator(Modifier.wrapContentSize()) }
        } else if (state.books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Make room for your next great read.", style = MaterialTheme.typography.titleMedium)
                    Text("Save books from Search to see them here. Explore these popular picks to get started.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Popular picks", style = MaterialTheme.typography.headlineSmall)
                    if (state.popularLoading) CircularProgressIndicator()
                    state.popularError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::loadPopular, enabled = !state.popularLoading) { Text("Try again") }
                    }
                    if (!state.popularLoading && state.popular.isEmpty() && state.popularError == null) {
                        Text("No popular picks are available right now. Find your next read in Search.")
                    }
                }
            }
            state.popular.forEach { row ->
                item(key = "popular-${row.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleLarge)
                        if (row.blurb.isNotBlank()) Text(row.blurb, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val books = row.books
                        if (books == null) CircularProgressIndicator()
                        else if (books.isEmpty()) Text("No books available in this row right now.")
                        else LazyRow(horizontalArrangement = Arrangement.spacedBy(tokens.gridSpacing)) {
                            rowItems(books, key = { it.key }) { book ->
                                Box(Modifier.width(tokens.coverMinWidth)) {
                                    BookTile(book, onShelf = false, onClick = { onOpenBook(book) })
                                }
                            }
                        }
                    }
                }
            }
        } else {
            items(state.books, key = { it.key }) { book ->
                Column {
                    BookTile(book, onShelf = true, onClick = { onOpenBook(book) })
                    Box(Modifier.align(Alignment.End)) {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${book.title}")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Remove from shelf") }, onClick = {
                                menuOpen = false
                                vm.remove(book)
                            })
                        }
                    }
                }
            }
        }
    }
}
