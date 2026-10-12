package com.mavislibrary.app.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.StaticLayout
import android.text.Layout
import android.text.TextUtils
import java.io.ByteArrayOutputStream

object PodcastArtwork {
    fun create(title: String): ByteArray {
        val hue = (title.hashCode().toLong() and 0x7fffffff).rem(360).toFloat()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 512f, 512f, Color.HSVToColor(floatArrayOf(hue, .55f, .45f)), Color.HSVToColor(floatArrayOf((hue + 70) % 360, .65f, .25f)), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 512f, 512f, paint)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 42f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        canvas.save(); canvas.translate(36f, 80f)
        StaticLayout.Builder.obtain(title, 0, title.length, text, 440).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(7).setEllipsize(TextUtils.TruncateAt.END).build().draw(canvas)
        canvas.restore()
        text.textSize = 22f
        canvas.drawText("Mavis Podcast Lounge", 36f, 466f, text)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
}
