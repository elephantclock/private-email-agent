package tech.elephantclock.privatemail.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class BrandCardStyle {
    /** White card on the off-white background: settings groups, lists. */
    Plain,

    /** Beige card for summaries such as "Today: 38 filed, 61 discarded". */
    Highlight,
}

@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    style: BrandCardStyle = BrandCardStyle.Plain,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color =
            when (style) {
                BrandCardStyle.Plain -> colors.surfaceContainerLowest
                BrandCardStyle.Highlight -> colors.primaryContainer
            },
        contentColor =
            when (style) {
                BrandCardStyle.Plain -> colors.onSurface
                BrandCardStyle.Highlight -> colors.onPrimaryContainer
            },
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
