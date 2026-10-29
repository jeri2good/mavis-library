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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.mavislibrary.app.data.model.Book
import kotlin.random.Random

internal val BOOK_WIDTH = 92.dp
internal val BOOK_SPACING = 10.dp
internal val WALL_HEIGHT = 172.dp
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

/** Books aren't all the same height; the key decides, so a book is always the same size. */
internal fun bookHeight(key: String): Dp = (128 + Math.floorMod(spread(key) / 360, 21)).dp

/** Warm back wall of one shelf bay, with the shade from the shelf above and the side uprights. */
@Composable
internal fun ShelfBay(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().height(WALL_HEIGHT).drawBehind {
        drawRect(Brush.verticalGradient(listOf(WallTop, WallBottom)))
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Black.copy(alpha = .2f), Color.Transparent), endY = 30.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)), startY = size.height - 26.dp.toPx()))
    }) {
        content()
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

/** The plank books stand on: lit top face and a grained front edge with a brass label plate. Its shadow falls on the bay below. */
@Composable
internal fun ShelfPlank(id: String, label: String) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.verticalGradient(listOf(Color(0xFF6B4426), WoodLight, Color(0xFFC89463)))))
        Box(Modifier.fillMaxWidth().height(24.dp).drawWithCache {
            val random = Random(id.hashCode())
            val lines = List(9) {
                val y = random.nextFloat() * size.height
                val amp = 1f + random.nextFloat() * 2.5f
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
        }, contentAlignment = Alignment.CenterStart) {
            BrassPlate(label, Modifier.padding(start = SHELF_EDGE + 10.dp))
        }
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
            Text(book.title, color = Color(0xFFF3E7CF), textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
                style = titleStyle(book.title))
            Spacer(Modifier.height(6.dp))
            Text(book.authorLine, color = Gilt, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 10.sp, lineHeight = 12.sp))
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
            Text(site.name, color = Color(0xFFF3E7CF), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp))
        }
        if (site.unread > 0) Ribbon(if (site.unread > 99) "99+" else "${site.unread}", Modifier.align(Alignment.TopEnd).padding(end = 8.dp))
    }
}

/** Long single words (Frankenstein) get a smaller size so they don't break mid-word on a narrow cover. */
private fun titleStyle(title: String): TextStyle {
    val long = (title.split(' ').maxOfOrNull { it.length } ?: 0) > 9
    return TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = if (long) 11.sp else 13.sp,
        lineHeight = if (long) 14.sp else 16.sp, hyphens = Hyphens.Auto)
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
        Text(text, Modifier.padding(top = 4.dp), color = Color.White, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp))
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
    val hue = hueOf(key)
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.hsv(hue, .45f, .46f), Color.hsv(hue, .55f, .27f))))
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
