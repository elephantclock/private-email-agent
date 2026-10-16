package tech.elephantclock.privatemail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tech.elephantclock.privatemail.R

private fun figtree(weight: FontWeight) =
    Font(
        resId = R.font.figtree,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

/** Figtree variable font (OFL), one instance per weight the design uses. */
val Figtree =
    FontFamily(
        figtree(FontWeight.Normal),
        figtree(FontWeight.Medium),
        figtree(FontWeight.SemiBold),
        figtree(FontWeight.Bold),
        figtree(FontWeight.ExtraBold),
    )

private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Double = size * 1.3,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Figtree,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.em,
)

/** Type scale taken from the mockups (sizes in sp). */
val BrandTypography =
    Typography(
        displaySmall = style(36, FontWeight.ExtraBold, 42.0),
        headlineLarge = style(28, FontWeight.ExtraBold, 32.0),
        headlineMedium = style(26, FontWeight.ExtraBold, 30.0),
        headlineSmall = style(22, FontWeight.Bold, 28.0),
        titleLarge = style(18, FontWeight.Bold, 24.0),
        titleMedium = style(15, FontWeight.Bold, 20.0),
        titleSmall = style(14, FontWeight.SemiBold, 20.0),
        bodyLarge = style(15, FontWeight.Normal, 21.0),
        bodyMedium = style(14, FontWeight.Normal, 20.0),
        bodySmall = style(13, FontWeight.Normal, 18.0),
        labelLarge = style(14, FontWeight.Bold, 20.0),
        labelMedium = style(13, FontWeight.SemiBold, 16.0),
        // Section labels and importance pills: small caps-style tracking.
        labelSmall = style(12, FontWeight.Bold, 16.0, letterSpacing = 0.1),
    )
