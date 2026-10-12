package com.mavislibrary.app.data.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.mavislibrary.app.ui.theme.Look
import com.mavislibrary.app.ui.theme.colorSchemeFor
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Hard-break long tokens as well as wrapping words; blank paragraphs remain blank. */
fun wrapVerse(text: String, width: Float, measure: (String) -> Float): List<String> {
    require(width > 0)
    return text.split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>(); var line = ""
        paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
            if (line.isNotEmpty() && measure("$line $word") <= width) line += " $word"
            else {
                if (line.isNotEmpty()) { lines += line; line = "" }
                word.codePoints().toArray().forEach { cp ->
                    val char = String(Character.toChars(cp))
                    if (line.isNotEmpty() && measure(line + char) > width) { lines += line; line = "" }
                    line += char
                }
            }
        }
        lines + line
    }
}

/** Deterministic renderer: no storage, clock, network or Context. Refuses text too long to fit legibly. */
fun renderVerseCard(reference: String, text: String, look: Look): Bitmap {
    require(reference.isNotBlank() && reference.length <= 120 && text.isNotBlank() && text.length <= 5000)
    val colors = colorSchemeFor(look)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.onBackground.toArgb(); typeface = Typeface.create("serif", Typeface.NORMAL) }
    var lines: List<String>
    var size = 52f
    while (true) {
        paint.textSize = size
        lines = wrapVerse(text, 888f, paint::measureText)
        if (lines.size * size * 1.35f <= 720f || size <= 24f) break
        size -= 2f
    }
    require(lines.size * size * 1.35f <= 720f) { "Please share a shorter passage." }
    val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(colors.background.toArgb())
    lines.forEachIndexed { i, line -> canvas.drawText(line, 96f, 120f + i * size * 1.35f, paint) }
    paint.color = colors.primary.toArgb(); paint.textSize = 34f
    while (paint.measureText(reference) > 888f && paint.textSize > 14f) paint.textSize -= 1f
    canvas.drawText(reference, 96f, 940f, paint)
    paint.textSize = 24f
    canvas.drawText("Mavis Library", 96f, 1010f, paint)
    return bitmap
}

/** Internal API until a screen has a selected verse. Caller passes the currently resolved Look. */
object ShareVerse {
    suspend fun share(context: Context, reference: String, text: String, look: Look) {
        if (context.kidsPrefs().state.first().enabled) return
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            // Expire yesterday's cards, without revoking a receiver's fresh attachment.
            dir.listFiles()?.filter { it.name.endsWith(".png") && it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
            val card = renderVerseCard(reference, text, look)
            try { File(dir, "${UUID.randomUUID()}.png").also { f -> f.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
            finally { card.recycle() }
        }
        if (context.kidsPrefs().state.first().enabled) { file.delete(); return }
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .setClipData(ClipData.newRawUri("Verse card", uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share verse").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
