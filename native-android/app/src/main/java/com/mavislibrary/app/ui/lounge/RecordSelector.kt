package com.mavislibrary.app.ui.lounge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.mavislibrary.app.ui.podcast.episodeHeadline
import com.mavislibrary.app.ui.podcast.episodeWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/** Label colour: the first site's, so a site's episodes match, else the episode's own. */
internal fun recordHue(episode: Episode) = com.mavislibrary.app.ui.shelf.hueOf(episode.job.sites.firstOrNull() ?: episode.job.id)

/**
 * Record-crate selector: filter chips, a snapping carousel of vinyl records (the centred one large, its
 * neighbours smaller, tilted and faded), and the centred record's headline, details and Play button.
 * [nowPlaying] is the media id of what the player has loaded; that record spins while [playing].
 */
@Composable
fun RecordSelector(
    shelves: Map<PodcastShelf, List<Episode>>,
    nowPlaying: String?,
    playing: Boolean,
    enabled: Boolean,
    onPlay: (Episode, List<Episode>) -> Unit,
    onToggle: () -> Unit,
    onDetails: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    polling: Map<String, String> = emptyMap(),
    menu: (@Composable ColumnScope.(Episode, () -> Unit) -> Unit)? = null,
) {
    var chosen by rememberSaveable { mutableStateOf<RecordFilter?>(null) }
    val filter = chosen ?: defaultRecordFilter(shelves)
    val crate = records(filter, shelves)
    Column(modifier.fillMaxWidth().testTag("record_selector"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordFilter.entries.forEach { f ->
                val count = records(f, shelves).size
                FilterChip(selected = f == filter, onClick = { chosen = f }, label = { Text(if (count > 0) "${f.label} · $count" else f.label) },
                    modifier = Modifier.testTag("record_chip_${f.name.lowercase()}"))
            }
        }
        if (crate.isEmpty()) {
            Text("Nothing here yet.", Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        key(filter) {
            val pager = rememberPagerState { crate.size }
            val scope = rememberCoroutineScope()
            val centred = crate[pager.currentPage.coerceIn(crate.indices)]
            fun isLoaded(e: Episode) = nowPlaying == e.saved.resumeKey
            fun primary(e: Episode) = when {
                e.job.status != "ready" -> onDetails(e)
                isLoaded(e) -> onToggle()
                else -> onPlay(e, upNext(e, shelves))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().drawBehind {
                // Warm jukebox glow behind the crate.
                drawRect(Brush.radialGradient(listOf(Color(0xFFFFB45C).copy(alpha = .16f), Color.Transparent), center, size.width * .5f))
            }) {
                val size = min(maxWidth * .64f, 280.dp)
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = (maxWidth - size) / 2),
                    pageSize = PageSize.Fixed(size),
                    beyondViewportPageCount = 2,
                    key = { crate[it].saved.key },
                    modifier = Modifier.fillMaxWidth().height(size + 24.dp),
                ) { page ->
                    val episode = crate[page]
                    val offset = (page - pager.currentPage) - pager.currentPageOffsetFraction
                    val distance = abs(offset).coerceAtMost(1f)
                    Record(
                        episode, size, spinning = playing && isLoaded(episode),
                        onTap = { if (page == pager.currentPage) primary(episode) else scope.launch { pager.animateScrollToPage(page) } },
                        onLabel = { if (page == pager.currentPage) onDetails(episode) else scope.launch { pager.animateScrollToPage(page) } },
                        modifier = Modifier.zIndex(1f - distance).graphicsLayer {
                            val scale = lerp(1f, .72f, distance)
                            scaleX = scale; scaleY = scale
                            alpha = lerp(1f, .5f, distance)
                            rotationY = -offset.coerceIn(-1.5f, 1.5f) * 28f
                            rotationZ = offset.coerceIn(-1.5f, 1.5f) * 5f
                            translationX = -offset.coerceIn(-1.5f, 1.5f) * size.toPx() * .22f
                            cameraDistance = 14f * density
                        },
                    )
                }
                CrateLip(Modifier.align(Alignment.BottomCenter))
            }
            val job = centred.job
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(episodeHeadline(job), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("record_headline"))
                when (centred.shelf) {
                    PodcastShelf.MAKING -> {
                        Text(stageLabel(job), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(progress = { (job.progress / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(.7f))
                        polling[job.id]?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    PodcastShelf.FAILED -> Text(job.error ?: "The studio couldn't make this one.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    else -> {
                        Text(episodeDetails(centred), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("record_subtitle"))
                        if (centred.shelf == PodcastShelf.CONTINUE) centred.heard?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(.7f)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val spinning = playing && isLoaded(centred)
                    Button(onClick = { primary(centred) }, enabled = enabled && job.status == "ready",
                        modifier = Modifier.weight(1f).height(56.dp).testTag("record_play")) {
                        Icon(if (spinning) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(when {
                            spinning -> "Pause"
                            isLoaded(centred) || centred.shelf == PodcastShelf.CONTINUE -> "Resume"
                            else -> "Play"
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { onDetails(centred) }, modifier = Modifier.testTag("record_info")) {
                        Icon(Icons.Outlined.Info, contentDescription = "Episode details for ${episodeHeadline(job)}")
                    }
                    if (menu != null) Box {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }, modifier = Modifier.testTag("record_menu")) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${job.title}")
                        }
                        DropdownMenu(open, { open = false }) { menu(centred) { open = false } }
                    }
                }
            }
        }
    }
}

private val LabelInk = Color(0xFF1E140C)

/** Front board of the record crate; the records stand behind it. */
@Composable
private fun CrateLip(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(30.dp).shadow(6.dp, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFFB07A48), Color(0xFF8A5A33), Color(0xFF5E3A1F))), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
        .drawBehind {
            drawLine(Color.White.copy(alpha = .25f), Offset(0f, 1f), Offset(size.width, 1f), 1.dp.toPx())
            listOf(.3f, .62f, .8f).forEach { y -> drawLine(Color(0xFF3B2312).copy(alpha = .22f), Offset(0f, size.height * y), Offset(size.width, size.height * y + 2f), .8.dp.toPx()) }
        })
}

@Composable
private fun Record(episode: Episode, size: Dp, spinning: Boolean, onTap: () -> Unit, onLabel: () -> Unit, modifier: Modifier = Modifier) {
    val job = episode.job
    val making = episode.shelf == PodcastShelf.MAKING
    val failed = episode.shelf == PodcastShelf.FAILED
    val headline = episodeHeadline(job)
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) while (isActive) {
            angle.animateTo(angle.value + 360f, tween(4_000, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    val hue = recordHue(episode)
    val status = when {
        failed -> "Couldn't be made"
        making -> stageLabel(job)
        else -> episodeWhen(job)
    }
    Box(
        modifier.size(size).shadow(if (making) 4.dp else 12.dp, CircleShape).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = if (job.status == "ready") "Play" else "Episode details", onClick = onTap)
            .semantics { contentDescription = "Record: $headline. $status" }
            .testTag("record_${job.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value; alpha = if (making) .55f else 1f }.vinyl(job.id, failed)) {
            if (making) {
                val r = this.size.minDimension * .2f + 5.dp.toPx()
                drawArc(Color.White.copy(alpha = .25f), 0f, 360f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx()))
                drawArc(Color(0xFFFFC857), -90f, (job.progress.toFloat() / 100f).coerceIn(0f, 1f) * 360f, false,
                    Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        // Fixed sheen: light doesn't turn with the record.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .13f), Color.Transparent, Color.Transparent,
                Color.White.copy(alpha = .08f), Color.Transparent, Color.Transparent)))
        }
        val labelColors = when {
            failed -> listOf(Color(0xFFB9B5AE), Color(0xFF8C8882))
            else -> listOf(Color.hsv(hue, .38f, .95f), Color.hsv(hue, .6f, .72f))
        }
        Box(
            Modifier.size(size * .4f).graphicsLayer { rotationZ = angle.value; alpha = if (making) .7f else 1f }.clip(CircleShape)
                .background(Brush.radialGradient(labelColors))
                .clickable(role = Role.Button, onClickLabel = "Episode details", onClick = onLabel)
                .testTag("record_label_${job.id}"),
            contentAlignment = Alignment.Center,
        ) {
            // Headline above the spindle hole, date or status below it, like a pressed label.
            Column(Modifier.fillMaxSize().padding(horizontal = size * .05f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).padding(top = size * .04f), contentAlignment = Alignment.BottomCenter) {
                    Text(headline, color = LabelInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = (size.value / 24f).coerceIn(8f, 12f).sp,
                            lineHeight = (size.value / 20f).coerceIn(10f, 14f).sp))
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    Text(status, color = if (failed) Color(0xFF7A1F1F) else LabelInk.copy(alpha = .75f), textAlign = TextAlign.Center, maxLines = 2,
                        style = TextStyle(fontSize = (size.value / 30f).coerceIn(7f, 10f).sp, fontWeight = FontWeight.Medium))
                }
            }
        }
        // Spindle hole stays put on top of the label.
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF0D0D0D)))
        if (failed) Canvas(Modifier.fillMaxSize()) { drawCrack(job.id) }
    }
}

/** Black vinyl: dark radial body, fine grooves, a few wider track gaps and a lighter rim. Dusty speckle when failed. */
private fun Modifier.vinyl(seed: String, failed: Boolean) = drawWithCache {
    val radius = size.minDimension / 2
    val inner = radius * .44f
    val outer = radius * .97f
    val grooves = 34
    val fine = Stroke(.6.dp.toPx())
    val gap = Stroke(1.6.dp.toPx())
    val random = Random(seed.hashCode())
    val gaps = List(3) { inner + (outer - inner) * (.25f + random.nextFloat() * .6f) }
    val dust = if (failed) List(70) { Offset(random.nextFloat() * size.width, random.nextFloat() * size.height) to (.5f + random.nextFloat()) } else emptyList()
    val body = Brush.radialGradient(listOf(Color(0xFF242424), Color(0xFF121212), Color(0xFF070707)), Offset(size.width / 2, size.height / 2), radius)
    onDrawBehind {
        drawCircle(body, radius)
        for (i in 0..grooves) {
            val r = inner + (outer - inner) * i / grooves
            drawCircle(Color.White.copy(alpha = if (i % 4 == 0) .07f else .035f), r, style = fine)
        }
        gaps.forEach { drawCircle(Color.Black.copy(alpha = .8f), it, style = gap) }
        drawCircle(Color.White.copy(alpha = .12f), radius - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        dust.forEach { (at, r) -> drawCircle(Color.White.copy(alpha = .18f), r.dp.toPx(), at) }
        if (failed) drawCircle(Color(0xFF6E6E6E).copy(alpha = .25f), radius)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrack(seed: String) {
    val random = Random(seed.hashCode() + 7)
    val path = Path()
    var x = size.width * (.62f + random.nextFloat() * .2f)
    var y = 0f
    path.moveTo(x, y)
    while (y < size.height * .42f) {
        x += (random.nextFloat() - .6f) * size.width * .08f
        y += size.height * (.04f + random.nextFloat() * .04f)
        path.lineTo(x, y)
    }
    drawPath(path, Color.Black.copy(alpha = .85f), style = Stroke(2.5.dp.toPx()))
    drawPath(path, Color.White.copy(alpha = .35f), style = Stroke(.8.dp.toPx()))
}
