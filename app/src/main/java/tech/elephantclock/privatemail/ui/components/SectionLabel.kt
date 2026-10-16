package tech.elephantclock.privatemail.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Uppercase gold heading above a group of cards, e.g. "VALUE FOLDERS". */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        color = if (danger) BrandTheme.colors.sectionLabelDanger else BrandTheme.colors.sectionLabel,
        style = MaterialTheme.typography.labelSmall,
    )
}
