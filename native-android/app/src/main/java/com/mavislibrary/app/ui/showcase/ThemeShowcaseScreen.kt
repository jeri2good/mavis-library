package com.mavislibrary.app.ui.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.ui.theme.LocalLookTokens
import com.mavislibrary.app.ui.theme.Look
import com.mavislibrary.app.ui.theme.MavisTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeShowcaseScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("showcase")) {
        TopAppBar(
            title = { Text("Themes") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        )
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Look.entries) { look -> MavisTheme(look) { LookSample(look) } }
        }
    }
}

/** A self-contained sample of one theme: type scale, buttons, chips, covers and a card. */
@Composable
fun LookSample(look: Look, modifier: Modifier = Modifier) {
    val tokens = LocalLookTokens.current
    Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(look.label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (look == Look.KIDS) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("Preview", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Text(look.blurb, style = MaterialTheme.typography.bodyLarge)
            Text("It is a truth universally acknowledged\u2026", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gridSpacing / 2)) {
                listOf("Emma", "Persuasion", "Psalms").forEachIndexed { i, title ->
                    val colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
                    Box(
                        Modifier.weight(1f).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium).background(colors[i]).padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.heightIn(min = tokens.minTouchTarget)) { Text(if (look == Look.KIDS) "Play!" else "Read") }
                FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = tokens.minTouchTarget)) { Text("Listen") }
                OutlinedButton(onClick = {}, modifier = Modifier.heightIn(min = tokens.minTouchTarget)) { Text("Save") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Classics") })
                FilterChip(selected = false, onClick = {}, label = { Text("Poetry") })
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Text(
                    "\u201cThe Lord is my shepherd; I shall not want.\u201d Psalm 23:1",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Preview(name = "Paper", widthDp = 380)
@Composable
private fun PaperPreview() = MavisTheme(Look.PAPER) { LookSample(Look.PAPER) }

@Preview(name = "Midnight", widthDp = 380)
@Composable
private fun MidnightPreview() = MavisTheme(Look.MIDNIGHT) { LookSample(Look.MIDNIGHT) }

@Preview(name = "Editorial", widthDp = 380)
@Composable
private fun EditorialPreview() = MavisTheme(Look.EDITORIAL) { LookSample(Look.EDITORIAL) }

@Preview(name = "Kids", widthDp = 380)
@Composable
private fun KidsPreview() = MavisTheme(Look.KIDS) { LookSample(Look.KIDS) }
