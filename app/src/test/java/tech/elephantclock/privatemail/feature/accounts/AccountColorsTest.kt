package tech.elephantclock.privatemail.feature.accounts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.ui.theme.DarkColorScheme
import tech.elephantclock.privatemail.ui.theme.LightColorScheme

/** Account colours are graphics, so they need 3:1 (WCAG 1.4.11) against the background and cards. */
class AccountColorsTest {
    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `one swatch per stored colour index, each with its own name`() {
        assertEquals(AccountEntity.COLOR_COUNT, AccountPalette.swatches.size)
        assertEquals(
            AccountPalette.swatches.size,
            AccountPalette.swatches
                .map { it.name }
                .toSet()
                .size,
        )
        assertEquals("indexes wrap", AccountPalette.swatch(0), AccountPalette.swatch(AccountEntity.COLOR_COUNT))
    }

    @Test
    fun `every swatch reaches 3 to 1 on the background and on cards, in both themes`() {
        val failures =
            AccountPalette.swatches.flatMapIndexed { i, s ->
                listOf(
                    "light/background" to ratio(s.light, LightColorScheme.background),
                    "light/card" to ratio(s.light, LightColorScheme.surfaceContainerLowest),
                    "dark/background" to ratio(s.dark, DarkColorScheme.background),
                    "dark/card" to ratio(s.dark, DarkColorScheme.surfaceContainerLowest),
                ).filter { it.second < 3.0 }.map { "$i ${it.first} %.2f".format(it.second) }
            }
        assertTrue("below 3:1: $failures", failures.isEmpty())
    }

    @Test
    fun `swatches in one theme are distinct colours`() {
        listOf(AccountPalette.swatches.map { it.light }, AccountPalette.swatches.map { it.dark }).forEach {
            assertEquals(it.size, it.toSet().size)
        }
    }
}
