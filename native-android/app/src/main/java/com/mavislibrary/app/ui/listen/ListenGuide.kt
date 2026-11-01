package com.mavislibrary.app.ui.listen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.data.prefs.SettingsStore
import com.mavislibrary.app.ui.lounge.devotionalTopics
import kotlinx.coroutines.launch

data class GuideCard(val title: String, val body: String)

/** The three first-run cards (mock G1–G3). */
val listenGuideCards = listOf(
    GuideCard("Everything to hear is on Listen", "New episodes are first, right where you left off, with your daily shows just below."),
    GuideCard("Tap ＋ New to make anything", "Choose one episode now, or a show that arrives every day."),
    GuideCard("A devotional, every evening", "Pick a site or a topic, choose a time, and tap Start. Change it any time in Settings."),
)

/** Shows the guide once, until Skip or Done stores the seen flag. Waits for the stored value so it never flashes. */
@Composable
fun ListenGuideHost(settings: SettingsStore) {
    val seen by settings.listenGuideSeen.collectAsStateWithLifecycle(initialValue = null)
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (seen == false && !dismissed) ListenGuide(onDone = { dismissed = true; scope.launch { settings.markListenGuideSeen() } })
}

@Composable
fun ListenGuide(onDone: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = page == listenGuideCards.lastIndex
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("listen_guide"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("guide_skip")) { Text("Skip", style = MaterialTheme.typography.titleMedium) }
                }
                // Sample art explains the controls; it is not tappable.
                Box(Modifier.fillMaxWidth().weight(1f).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    when (page) { 0 -> RecordArt(); 1 -> NewArt(); else -> DevotionalArt() }
                }
                val card = listenGuideCards[page]
                Text(card.title, Modifier.semantics { heading() }.testTag("guide_title"), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                Text(card.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth().semantics { contentDescription = "Card ${page + 1} of ${listenGuideCards.size}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    listenGuideCards.indices.forEach { i ->
                        Box(Modifier.height(8.dp).width(if (i == page) 24.dp else 8.dp).clip(CircleShape)
                            .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { if (last) onDone() else page++ }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("guide_next")) {
                    Text(if (last) "Done" else "Next", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun RecordArt() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(220.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFF242424), Color(0xFF070707))), r)
            for (i in 0..24) drawCircle(Color.White.copy(alpha = .05f), r * (.45f + .5f * i / 24f), style = Stroke(1f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFD08BE8), Color(0xFF8E44B8))), r * .4f)
            drawCircle(Color(0xFF0D0D0D), r * .04f)
        }
        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFB07A48), Color(0xFF5E3A1F)))))
    }
}

@Composable
private fun NewArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
        ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ArtRow(Icons.Outlined.CalendarMonth, "Every day", "A devotional or your briefing", MaterialTheme.colorScheme.secondaryContainer)
        ArtRow(Icons.AutoMirrored.Outlined.MenuBook, "Make one now", "A chapter, book, article or topic", MaterialTheme.colorScheme.surfaceContainer)
    }
}

@Composable
private fun ArtRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.large, color = color, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevotionalArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.padding(16.dp)) {
                Text("Today's devotion", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Alpha and Omega", style = MaterialTheme.typography.titleLarge)
                Text("Revelation 22:12-21", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { devotionalTopics.forEach { AssistChip(onClick = {}, label = { Text(it) }) } }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
                Text("7:00 PM", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("every day", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
