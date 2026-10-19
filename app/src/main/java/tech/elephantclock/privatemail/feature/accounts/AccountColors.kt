package tech.elephantclock.privatemail.feature.accounts

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.DarkBrandColors
import tech.elephantclock.privatemail.ui.theme.LightBrandColors

/** One account colour: a light- and a dark-theme shade of the same brand hue, and its name. */
data class AccountSwatch(
    val light: Color,
    val dark: Color,
    @StringRes val name: Int,
)

/**
 * Account colours (T52): the inbox's five T02 hues in the order they were handed out before
 * (so existing accounts keep theirs), each in a shade that reaches 3:1 against the background
 * and cards in its theme (WCAG non-text contrast; `AccountColorsTest`). Beige and gold were too
 * pale on off-white and dark red too dark on the dark theme, so those use the theme's own
 * darker or lighter tokens. Colour is never the only cue: chips and rows carry the account name.
 * The index stored with the account ([AccountEntity.colorIndex]) picks one.
 */
object AccountPalette {
    val swatches: List<AccountSwatch> =
        listOf(
            AccountSwatch(Brand.Cognac, Brand.Cognac, R.string.accounts_colour_cognac),
            AccountSwatch(LightBrandColors.sectionLabel, Brand.Gold, R.string.accounts_colour_gold),
            AccountSwatch(Color(0xFF8A7B62), Brand.Beige, R.string.accounts_colour_sand),
            AccountSwatch(Brand.DarkRed, DarkBrandColors.sectionLabelDanger, R.string.accounts_colour_wine),
            AccountSwatch(Color(0xFF6F8F6A), Color(0xFF8FAF89), R.string.accounts_colour_sage),
        )

    init {
        check(swatches.size == AccountEntity.COLOR_COUNT) { "one swatch per stored colour index" }
    }

    fun swatch(index: Int): AccountSwatch = swatches[index.mod(swatches.size)]

    fun color(
        index: Int,
        dark: Boolean,
    ): Color = swatch(index).let { if (dark) it.dark else it.light }
}

/** The account colour for [index] in the current theme (for chips, dots and list stripes). */
@Composable
@ReadOnlyComposable
fun accountColour(index: Int): Color =
    AccountPalette.color(index, dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
