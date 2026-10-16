package tech.elephantclock.privatemail.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.ui.theme.BrandColors
import tech.elephantclock.privatemail.ui.theme.DarkBrandColors
import tech.elephantclock.privatemail.ui.theme.DarkColorScheme
import tech.elephantclock.privatemail.ui.theme.LightBrandColors
import tech.elephantclock.privatemail.ui.theme.LightColorScheme

/** Every text/background token pair the components use must reach WCAG AA (4.5:1). */
class ContrastTest {
    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun pairs(
        s: ColorScheme,
        b: BrandColors,
    ) = mapOf(
        "onBackground/background" to (s.onBackground to s.background),
        "onSurface/card" to (s.onSurface to s.surfaceContainerLowest),
        "onSurfaceVariant/card" to (s.onSurfaceVariant to s.surfaceContainerLowest),
        "onPrimaryContainer/highlight" to (s.onPrimaryContainer to s.primaryContainer),
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onHeader/header" to (b.onHeader to b.header),
        "onHeaderMuted/header" to (b.onHeaderMuted to b.header),
        "onAccent/accent" to (b.onAccent to b.accent),
        "sectionLabel/background" to (b.sectionLabel to s.background),
        "sectionLabelDanger/background" to (b.sectionLabelDanger to s.background),
        "textMuted/card" to (b.textMuted to s.surfaceContainerLowest),
        "textMuted/background" to (b.textMuted to s.background),
        "urgent" to (b.onUrgent to b.urgent),
        "high" to (b.onHigh to b.high),
        "normal" to (b.onNormal to b.normal),
        "low" to (b.onLow to b.low),
    )

    private fun check(
        name: String,
        s: ColorScheme,
        b: BrandColors,
    ) {
        val failures =
            pairs(s, b)
                .mapValues { (_, p) -> ratio(p.first, p.second) }
                .filterValues { it < 4.5 }
        assertTrue("$name below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun lightThemeTextContrast() = check("light", LightColorScheme, LightBrandColors)

    @Test
    fun darkThemeTextContrast() = check("dark", DarkColorScheme, DarkBrandColors)
}
