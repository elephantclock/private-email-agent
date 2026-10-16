package tech.elephantclock.privatemail.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** ElephantClock brand palette. */
object Brand {
    val DarkGreen = Color(0xFF2D3628)
    val DarkRed = Color(0xFF913D3D)
    val Gold = Color(0xFFC29B40)
    val Cognac = Color(0xFFC06E3F)
    val Beige = Color(0xFFE8DCC6)
    val OffWhite = Color(0xFFF5F0E8)
    val White = Color(0xFFFFFFFF)
}

/** Tokens Material 3's [ColorScheme] has no slot for. */
@Immutable
data class BrandColors(
    /** Dark app bar behind titles and account chips, in both themes. */
    val header: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val headerOutline: Color,
    /** Gold used as a fill (selected chip) with [onAccent] text. */
    val accent: Color,
    val onAccent: Color,
    val sectionLabel: Color,
    val sectionLabelDanger: Color,
    val textMuted: Color,
    val divider: Color,
    val switchOn: Color,
    val switchOff: Color,
    val switchThumb: Color,
    val urgent: Color,
    val onUrgent: Color,
    val high: Color,
    val onHigh: Color,
    val normal: Color,
    val onNormal: Color,
    val low: Color,
    val onLow: Color,
)

val LightBrandColors =
    BrandColors(
        header = Brand.DarkGreen,
        onHeader = Brand.OffWhite,
        onHeaderMuted = Brand.Beige,
        headerOutline = Color(0xFF5B6753),
        accent = Brand.Gold,
        onAccent = Brand.DarkGreen,
        // The mockups' #8A6A1F is 4.45:1 on off-white; darkened to pass 4.5:1.
        sectionLabel = Color(0xFF7F6118),
        sectionLabelDanger = Color(0xFF6E2B2B),
        textMuted = Color(0xFF5C6356),
        divider = Color(0xFFEFE7D8),
        switchOn = Brand.DarkGreen,
        switchOff = Color(0xFFB9AE98),
        switchThumb = Brand.White,
        urgent = Brand.DarkRed,
        onUrgent = Brand.White,
        high = Color(0xFFF1D9C7),
        onHigh = Color(0xFF7A3A15),
        normal = Brand.Beige,
        onNormal = Brand.DarkGreen,
        low = Color(0xFFEFE7D8),
        onLow = Color(0xFF5C6356),
    )

val DarkBrandColors =
    BrandColors(
        header = Brand.DarkGreen,
        onHeader = Brand.OffWhite,
        onHeaderMuted = Brand.Beige,
        headerOutline = Color(0xFF5B6753),
        accent = Brand.Gold,
        onAccent = Brand.DarkGreen,
        sectionLabel = Brand.Gold,
        sectionLabelDanger = Color(0xFFE8A0A0),
        textMuted = Color(0xFFB5B9AE),
        divider = Color(0xFF2E3729),
        switchOn = Brand.Gold,
        switchOff = Color(0xFF5B6753),
        switchThumb = Brand.OffWhite,
        urgent = Brand.DarkRed,
        onUrgent = Brand.White,
        high = Color(0xFF5A2E14),
        onHigh = Color(0xFFF1D9C7),
        normal = Color(0xFF3A4534),
        onNormal = Brand.Beige,
        low = Color(0xFF2E3729),
        onLow = Color(0xFFB5B9AE),
    )

val LightColorScheme: ColorScheme =
    lightColorScheme(
        primary = Brand.DarkGreen,
        onPrimary = Brand.OffWhite,
        primaryContainer = Brand.Beige,
        onPrimaryContainer = Brand.DarkGreen,
        secondary = Brand.Cognac,
        onSecondary = Brand.White,
        tertiary = Brand.Gold,
        onTertiary = Brand.DarkGreen,
        error = Brand.DarkRed,
        onError = Brand.White,
        background = Brand.OffWhite,
        onBackground = Color(0xFF1E241B),
        surface = Brand.OffWhite,
        onSurface = Color(0xFF1E241B),
        surfaceContainerLowest = Brand.White,
        surfaceContainer = Brand.White,
        surfaceVariant = Brand.Beige,
        onSurfaceVariant = Color(0xFF4E5549),
        outline = Color(0xFFCFC3AA),
        outlineVariant = Color(0xFFE3D8C3),
    )

val DarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = Brand.Gold,
        onPrimary = Brand.DarkGreen,
        primaryContainer = Brand.DarkGreen,
        onPrimaryContainer = Brand.Beige,
        secondary = Brand.Cognac,
        onSecondary = Brand.White,
        tertiary = Brand.Beige,
        onTertiary = Brand.DarkGreen,
        error = Color(0xFFE8A0A0),
        onError = Color(0xFF3B1414),
        background = Color(0xFF161B14),
        onBackground = Brand.OffWhite,
        surface = Color(0xFF161B14),
        onSurface = Brand.OffWhite,
        surfaceContainerLowest = Color(0xFF20271D),
        surfaceContainer = Color(0xFF20271D),
        surfaceVariant = Color(0xFF2E3729),
        onSurfaceVariant = Color(0xFFB5B9AE),
        outline = Color(0xFF5B6753),
        outlineVariant = Color(0xFF2E3729),
    )

val LocalBrandColors = staticCompositionLocalOf { LightBrandColors }
