package com.mavislibrary.app.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.mavislibrary.app.data.model.Book
import kotlin.random.Random

internal val BOOK_WIDTH = 92.dp
internal val BOOK_SPACING = 10.dp
internal val WALL_HEIGHT = 184.dp
internal val SHELF_EDGE = 12.dp
internal val WallText = Color(0xFFEADBC8)

private val WallTop = Color(0xFF2B1D14)
private val WallBottom = Color(0xFF3E2B1E)
private val WoodLight = Color(0xFFB07A48)
private val WoodMid = Color(0xFF8A5A33)
private val WoodDark = Color(0xFF5E3A1F)
private val Grain = Color(0xFF3B2312)
private val Gilt = Color(0xFFD9B96A)
private val BookShape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 4.dp, bottomEnd = 4.dp)

/** Stable, well-spread hash: neighbouring keys like "gutenberg:1" and "gutenberg:2" land far apart. */
internal fun spread(key: String): Int {
    var h = key.hashCode()
    h = (h xor (h ushr 16)) * 0x45d9f3b
    h = (h xor (h ushr 16)) * 0x45d9f3b
    return h xor (h ushr 16)
}

/** Stable 0..359 hue from a key, so a book or site keeps its colour. */
internal fun hueOf(key: String) = Math.floorMod(spread(key), 360).toFloat()

/** Leather bindings for books without a cover (and site books): lit top colour, shaded bottom colour. */
internal enum class Leather(val top: Color, val bottom: Color) {
    OXBLOOD(Color(0xFF6E2026), Color(0xFF3A0E12)),
    NAVY(Color(0xFF263A5E), Color(0xFF111C30)),
    FOREST(Color(0xFF2A4E35), Color(0xFF13281A)),
    MUSTARD(Color(0xFF9C7526), Color(0xFF574011)),
    TEAL(Color(0xFF22595B), Color(0xFF0F3133)),
    BROWN(Color(0xFF6E4829), Color(0xFF3A2413)),
    PLUM(Color(0xFF5C2B50), Color(0xFF32152B)),
    CHARCOAL(Color(0xFF424244), Color(0xFF1C1C1E)),
    TAN(Color(0xFF8C6743), Color(0xFF4F371E)),
    SLATE(Color(0xFF3A5570), Color(0xFF1B2B3B)),
}

/** The binding a key always gets. */
internal fun leatherOf(key: String) = Leather.entries[Math.floorMod(spread(key), Leather.entries.size)]

/** Books aren't all the same height; the key decides, so a book is always the same size. */
internal fun bookHeight(key: String): Dp = (128 + Math.floorMod(spread(key) / 360, 21)).dp

/** Warm back wall of one shelf bay, with its brass category plate at top-left above the books, shade from above, and side uprights. */
@Composable
internal fun ShelfBay(id: String, label: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().height(WALL_HEIGHT).drawBehind {
        drawRect(Brush.verticalGradient(listOf(WallTop, WallBottom)))
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .52f), Color.Black.copy(alpha = .18f), Color.Transparent), endY = 24.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)), startY = size.height - 26.dp.toPx()))
    }) {
        BrassPlate(label, Modifier.align(Alignment.TopStart).padding(start = SHELF_EDGE + 8.dp, top = 9.dp).testTag("shelf_plate_$id"))
        Box(Modifier.fillMaxSize().padding(top = 32.dp), content = content)
        Upright(Modifier.align(Alignment.CenterStart))
        Upright(Modifier.align(Alignment.CenterEnd), flip = true)
    }
}

@Composable
private fun Upright(modifier: Modifier, flip: Boolean = false) {
    val colors = listOf(WoodDark, WoodMid, WoodLight, WoodMid).let { if (flip) it.reversed() else it }
    Box(modifier.width(SHELF_EDGE).fillMaxHeight().background(Brush.horizontalGradient(colors)).drawBehind {
        val x = if (flip) 0f else size.width
        drawLine(Color.Black.copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    })
}

/** The plank books stand on: lit top face and a grained front edge. Its shadow falls on the bay below. */
@Composable
internal fun ShelfPlank(id: String) {
    Column(Modifier.fillMaxWidth().testTag("shelf_plank_$id")) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.verticalGradient(listOf(Color(0xFF6B4426), WoodLight, Color(0xFFC89463)))))
        Box(Modifier.fillMaxWidth().height(18.dp).drawWithCache {
            val random = Random(id.hashCode())
            val lines = List(8) {
                val y = random.nextFloat() * size.height
                val amp = 1f + random.nextFloat() * 2.2f
                val wave = 40f + random.nextFloat() * 120f
                val phase = random.nextFloat() * 6f
                val path = Path().apply {
                    moveTo(0f, y)
                    var x = 0f
                    while (x <= size.width) { lineTo(x, y + amp * kotlin.math.sin(x / wave + phase)); x += 12f }
                }
                path to (.10f + random.nextFloat() * .18f)
            }
            val stroke = Stroke(width = .8.dp.toPx())
            val base = Brush.verticalGradient(listOf(WoodMid, WoodDark))
            onDrawBehind {
                drawRect(base)
                lines.forEach { (path, alpha) -> drawPath(path, Grain.copy(alpha = alpha), style = stroke) }
                drawLine(Color.White.copy(alpha = .18f), Offset(0f, .5f), Offset(size.width, .5f), 1.dp.toPx())
            }
        })
    }
}

@Composable
private fun BrassPlate(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(19.dp).shadow(2.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFE9CF83), Color(0xFFC09445), Color(0xFF9A7029))))
            .border(.5.dp, Color(0xFF5C4115), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Screw()
        Text(
            label.uppercase(), modifier = Modifier.padding(horizontal = 6.dp), maxLines = 1,
            style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp,
                color = Color(0xFF3F2C0C), shadow = Shadow(Color.White.copy(alpha = .55f), Offset(0f, 1.2f))),
        )
        Screw()
    }
}

@Composable
private fun Screw() {
    Box(Modifier.size(4.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFF6E3A8), Color(0xFF6E5020)))))
}

/** Text on the back wall (empty and loading states). */
@Composable
internal fun WallMessage(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = WallText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif), textAlign = TextAlign.Center)
}

/** A book standing on the shelf: cover (or a coloured cloth cover with the title), a spine edge, and a drop shadow. */
@Composable
internal fun ShelfBook(book: Book, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    BookBody(
        key = book.key,
        description = "${book.title}, ${book.authorLine}",
        onClick = onClick,
        onLongClick = onLongClick,
        longClickLabel = "Options for ${book.title}",
        modifier = Modifier.testTag("shelf_book_${book.key}"),
    ) {
        ClothCover(book.key) {
            FitText(book.title, TitleStyle, Cream, maxLines = 4, sizes = TITLE_SIZES)
            Spacer(Modifier.height(6.dp))
            FitText(book.authorLine, AuthorStyle, Gilt, maxLines = 2, sizes = AUTHOR_SIZES)
        }
        if (book.coverUrl != null) AsyncImage(book.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A followed site as a book: cloth colour from its id, emblem, serif name, and an unread ribbon. */
@Composable
internal fun SiteShelfBook(site: SiteBook, onClick: () -> Unit) {
    BookBody(
        key = site.id,
        description = "${site.name} site" + if (site.unread > 0) ", ${site.unread} unread" else "",
        onClick = onClick,
        modifier = Modifier.testTag("shelf_site_${site.id}"),
    ) {
        ClothCover(site.id) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Gilt).border(1.dp, Color(0xFF7A5A1C), CircleShape), contentAlignment = Alignment.Center) {
                val letter = @Composable {
                    Text(site.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "S", color = Color(0xFF3F2C0C),
                        style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp))
                }
                val icon = faviconUrl(site.siteUrl)
                if (icon == null) letter()
                else SubcomposeAsyncImage(icon, contentDescription = null, modifier = Modifier.size(22.dp), loading = { letter() }, error = { letter() })
            }
            Spacer(Modifier.height(8.dp))
            FitText(site.name, TitleStyle, Cream, maxLines = 3, sizes = TITLE_SIZES)
        }
        if (site.unread > 0) Ribbon(
            if (site.unread > 99) "99+" else "${site.unread}",
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp).testTag("shelf_site_unread_${site.id}"),
        )
    }
}

private val Cream = Color(0xFFF3E7CF)
private val TitleStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
private val AuthorStyle = TextStyle(fontFamily = FontFamily.Serif)
private val TITLE_SIZES = listOf(14f, 13f, 12f, 11f, 10f, 9f)
private val AUTHOR_SIZES = listOf(10f, 9f, 8f)
/** Text width on a cover: the book minus ClothCover's side padding. */
private val COVER_TEXT_WIDTH = BOOK_WIDTH - 18.dp

/** Cover text lines and the size they fit at. */
internal data class FittedText(val lines: List<String>, val sizeSp: Float)

/**
 * Wraps [text] at spaces only (never mid-word), at the largest of [sizes] where every word fits [maxWidth] and the
 * result is at most [maxLines]. Only when even the smallest size can't fit does it cut words or the last line with "…".
 */
internal fun fitText(text: String, maxWidth: Float, maxLines: Int, sizes: List<Float>, width: (String, Float) -> Float): FittedText {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    fun clip(s: String, size: Float): String {
        if (width(s, size) <= maxWidth) return s
        var cut = s
        while (cut.isNotEmpty() && width("${cut.trimEnd()}…", size) > maxWidth) cut = cut.dropLast(1)
        return "${cut.trimEnd()}…"
    }
    fun wrap(words: List<String>, size: Float): List<String> = buildList {
        var line = ""
        words.forEach { word ->
            val joined = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || width(joined, size) <= maxWidth) line = joined else { add(line); line = word }
        }
        if (line.isNotEmpty()) add(line)
    }
    sizes.forEach { size ->
        if (words.all { width(it, size) <= maxWidth }) wrap(words, size).takeIf { it.size <= maxLines }?.let { return FittedText(it, size) }
    }
    val size = sizes.last()
    val lines = wrap(words.map { clip(it, size) }, size)
    if (lines.size <= maxLines) return FittedText(lines, size)
    val kept = lines.take(maxLines)
    return FittedText(kept.dropLast(1) + clip("${kept.last()} ${lines[maxLines]}", size).let { if (it.endsWith("…")) it else "$it…" }, size)
}

/** Cover text fitted by [fitText]; the lines are laid out exactly as measured. */
@Composable
private fun FitText(text: String, style: TextStyle, color: Color, maxLines: Int, sizes: List<Float>) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val maxWidth = with(LocalDensity.current) { COVER_TEXT_WIDTH.toPx() }
    val fitted = remember(text, maxWidth) {
        fitText(text, maxWidth, maxLines, sizes) { s, size -> measurer.measure(s, style.copy(fontSize = size.sp), softWrap = false, maxLines = 1).size.width.toFloat() }
    }
    Text(fitted.lines.joinToString("\n"), color = color, textAlign = TextAlign.Center, softWrap = false, maxLines = maxLines,
        style = style.copy(fontSize = fitted.sizeSp.sp, lineHeight = (fitted.sizeSp * 1.22f).sp))
}

internal fun faviconUrl(siteUrl: String): String? = runCatching {
    val uri = java.net.URI(siteUrl)
    if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) "https://${uri.host}/favicon.ico" else null
}.getOrNull()

private val RibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
    lineTo(size.width / 2, size.height - size.width / 2.4f); lineTo(0f, size.height); close()
}

@Composable
private fun Ribbon(text: String, modifier: Modifier = Modifier) {
    Box(modifier.width(24.dp).height(38.dp).shadow(2.dp, RibbonShape).background(Brush.verticalGradient(listOf(Color(0xFFC62828), Color(0xFF8E1B1B))), RibbonShape),
        contentAlignment = Alignment.TopCenter) {
        Text(text, Modifier.padding(top = 4.dp), color = Color.White, maxLines = 1, softWrap = false,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp))
    }
}

@Composable
private fun BookBody(
    key: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.width(BOOK_WIDTH).height(bookHeight(key))
            .shadow(7.dp, BookShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(BookShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        content()
        // Spine edge on the left and a soft light across the cover so it reads as a physical book.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = .5f), .05f to Color.Black.copy(alpha = .18f), .075f to Color.White.copy(alpha = .16f),
            .11f to Color.Transparent, .8f to Color.Transparent, 1f to Color.Black.copy(alpha = .14f),
        )))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent, Color.Black.copy(alpha = .12f)))))
    }
}

@Composable
private fun ClothCover(key: String, content: @Composable ColumnScope.() -> Unit) {
    val leather = leatherOf(key)
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(leather.top, leather.bottom)))
            .drawBehind {
                val inset = 10.dp.toPx(); val w = 1.dp.toPx()
                listOf(inset, inset + 3.dp.toPx(), size.height - inset, size.height - inset - 3.dp.toPx()).forEach { y ->
                    drawLine(Gilt.copy(alpha = .75f), Offset(inset, y), Offset(size.width - inset / 2, y), w)
                }
            }
            .padding(start = 12.dp, end = 6.dp, top = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}
