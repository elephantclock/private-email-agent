package tech.elephantclock.privatemail.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandColors
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** UI importance levels. T10 adds the domain `Importance`; map it onto this. */
enum class ImportanceLevel(
    @param:StringRes val label: Int,
) {
    Urgent(R.string.importance_urgent),
    High(R.string.importance_high),
    Normal(R.string.importance_normal),
    Low(R.string.importance_low),
}

private fun BrandColors.pillColors(level: ImportanceLevel): Pair<Color, Color> =
    when (level) {
        ImportanceLevel.Urgent -> urgent to onUrgent
        ImportanceLevel.High -> high to onHigh
        ImportanceLevel.Normal -> normal to onNormal
        ImportanceLevel.Low -> low to onLow
    }

/** Small uppercase tag shown before the sender in message lists. */
@Composable
fun ImportancePill(
    level: ImportanceLevel,
    modifier: Modifier = Modifier,
) {
    val (container, content) = BrandTheme.colors.pillColors(level)
    Text(
        text = stringResource(level.label).uppercase(),
        modifier =
            modifier
                .background(container, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        color = content,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.06.em),
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
    )
}
