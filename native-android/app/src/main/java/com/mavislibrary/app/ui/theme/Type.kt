package com.mavislibrary.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.mavislibrary.app.R

// Variable fonts bundled with the web app (converted from @fontsource-variable, OFL-1.1).
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight, style: FontStyle = FontStyle.Normal) =
    weights.map { w ->
        Font(res, weight = w, style = style, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    }

private val standardWeights = arrayOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

val Literata = FontFamily(
    variable(R.font.literata_variable, *standardWeights) +
        variable(R.font.literata_italic_variable, FontWeight.Normal, style = FontStyle.Italic),
)
val Fraunces = FontFamily(variable(R.font.fraunces_variable, *standardWeights, FontWeight.Black))
val SourceSerif = FontFamily(variable(R.font.source_serif_variable, *standardWeights))
val Atkinson = FontFamily(variable(R.font.atkinson_variable, *standardWeights, FontWeight.ExtraBold))

private fun typography(display: FontFamily, body: FontFamily, displayWeight: FontWeight, scale: Float = 1f, tightDisplay: Boolean = false): Typography {
    val base = Typography()
    fun TextStyle.disp() = copy(fontFamily = display, fontWeight = displayWeight, fontSize = fontSize * scale, lineHeight = lineHeight * scale,
        letterSpacing = if (tightDisplay) (-0.02).em else letterSpacing)
    fun TextStyle.body() = copy(fontFamily = body, fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    return Typography(
        displayLarge = base.displayLarge.disp(),
        displayMedium = base.displayMedium.disp(),
        displaySmall = base.displaySmall.disp(),
        headlineLarge = base.headlineLarge.disp(),
        headlineMedium = base.headlineMedium.disp(),
        headlineSmall = base.headlineSmall.disp(),
        titleLarge = base.titleLarge.disp(),
        titleMedium = base.titleMedium.body().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.body().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.body().copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.body().copy(fontWeight = FontWeight.Medium),
    )
}

val PaperTypography = typography(display = Fraunces, body = Literata, displayWeight = FontWeight.SemiBold)
val MidnightTypography = typography(display = SourceSerif, body = Atkinson, displayWeight = FontWeight.Medium)
val EditorialTypography = typography(display = Atkinson, body = Atkinson, displayWeight = FontWeight.ExtraBold, tightDisplay = true)
val KidsTypography = typography(display = Atkinson, body = Atkinson, displayWeight = FontWeight.ExtraBold, scale = 1.25f)
