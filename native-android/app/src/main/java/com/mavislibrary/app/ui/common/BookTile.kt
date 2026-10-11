package com.mavislibrary.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.ui.theme.LocalLookTokens

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
fun BookTile(book: Book, onShelf: Boolean, onClick: () -> Unit) {
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

