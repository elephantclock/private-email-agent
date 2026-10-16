package tech.elephantclock.privatemail.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.ui.theme.BrandTheme

private val TrackWidth = 44.dp
private val TrackHeight = 28.dp
private val ThumbSize = 22.dp
private val ThumbInset = 3.dp

/** Compact 44 x 28 dp switch from the mockups, with a 48 dp touch target. */
@Composable
fun BrandSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = BrandTheme.colors
    val track by animateColorAsState(if (checked) colors.switchOn else colors.switchOff, label = "track")
    val thumbX by animateDpAsState(
        if (checked) TrackWidth - ThumbSize - ThumbInset * 2 else 0.dp,
        label = "thumb",
    )
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TrackWidth, TrackHeight)
                .background(track, CircleShape)
                .padding(ThumbInset),
        ) {
            Box(
                Modifier
                    .offset { IntOffset(thumbX.roundToPx(), 0) }
                    .size(ThumbSize)
                    .background(colors.switchThumb, CircleShape),
            )
        }
    }
}
