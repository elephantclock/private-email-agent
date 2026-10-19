package tech.elephantclock.privatemail.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.elephantclock.privatemail.feature.detail.DetailRoute
import tech.elephantclock.privatemail.feature.detail.DetailViewModel
import tech.elephantclock.privatemail.feature.reply.ReplyRoute
import tech.elephantclock.privatemail.feature.reply.ReplyViewModel
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import kotlin.math.max
import kotlin.math.min

/*
 * Flex mode (T4A, mockup 10): with the inner screen half-open like a laptop, read above the
 * hinge and act below it. Every split uses the real hinge bounds from WindowManager's
 * FoldingFeature (via FoldPosture.Tabletop), so nothing is drawn under an occluding hinge.
 */

/** How a pane splits around the hinge: [aboveDp] of content, then [hingeDp] kept clear. */
@Immutable
data class FlexSplit(
    val aboveDp: Float,
    val hingeDp: Float,
)

/**
 * Where the hinge cuts a pane that starts [paneTopDp] from the window's top and is
 * [paneHeightDp] tall. Null when the device isn't in tabletop posture or the hinge misses the
 * pane (then the pane lays out as usual).
 */
fun flexSplitOf(
    posture: FoldPosture,
    paneTopDp: Float,
    paneHeightDp: Float,
): FlexSplit? {
    if (posture !is FoldPosture.Tabletop) return null
    val paneBottom = paneTopDp + paneHeightDp
    if (posture.hingeBottomDp <= paneTopDp || posture.hingeTopDp >= paneBottom) return null
    val above = (posture.hingeTopDp - paneTopDp).coerceAtLeast(0f)
    val hinge = (min(posture.hingeBottomDp, paneBottom) - max(posture.hingeTopDp, paneTopDp)).coerceAtLeast(0f)
    return FlexSplit(above, hinge)
}

/** The band drawn over the fold is at least this tall, so the fold reads as a divider. */
const val MIN_HINGE_BAND_DP = 12f

/**
 * Splits a pane at the hinge in Flex mode: [top] above the fold for reading, [bottom] below it
 * for replying and actions, the hinge itself kept clear. Flat (or a book posture), it's one
 * column: [top] taking the space, then [bottom]. Onboarding runs outside the shell, so it
 * passes its [posture]; panes in the shell read it from [LocalFoldPosture].
 */
@Composable
fun HingeSplit(
    modifier: Modifier = Modifier,
    posture: FoldPosture = LocalFoldPosture.current,
    bottomColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    top: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    var paneTopDp by remember { mutableFloatStateOf(0f) }
    var paneHeightDp by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val split = flexSplitOf(posture, paneTopDp, paneHeightDp)
    Column(
        modifier.fillMaxSize().onPlaced {
            paneTopDp = it.positionInWindow().y / density
            paneHeightDp = it.size.height / density
        },
    ) {
        if (split == null) {
            Column(Modifier.weight(1f), content = top)
            Column(content = bottom)
        } else {
            Column(Modifier.fillMaxWidth().height(split.aboveDp.dp), content = top)
            HingeBand(split.hingeDp)
            Column(Modifier.fillMaxWidth().weight(1f).background(bottomColor), content = bottom)
        }
    }
}

/** Marks the fold: the hinge's own height (zero on the Fold7), at least a thin gold rule. */
@Composable
private fun HingeBand(heightDp: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(max(heightDp, MIN_HINGE_BAND_DP).dp)
            .heightIn(min = MIN_HINGE_BAND_DP.dp)
            .background(BrandTheme.colors.header)
            .testTag("hinge_band"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 60.dp, height = 4.dp).background(BrandTheme.colors.accent, RoundedCornerShape(2.dp)))
    }
}

/**
 * The open email and its reply editor in one pane. Flat or folded: the email, or (after Reply)
 * the full editor in its place. Flex mode: the email above the hinge and, once replying, a
 * compact editor below it (mockup 10). Whether the editor is open is saveable and the draft
 * lives in its ViewModel (same keys in every posture), so both survive flat ↔ half-open ↔
 * folded and the activity being recreated.
 */
@Composable
fun FlexReadingDetail(
    messageId: Long,
    detailViewModel: () -> DetailViewModel,
    replyViewModel: () -> ReplyViewModel,
) {
    var replying by rememberSaveable(messageId) { mutableStateOf(false) }
    // A new editor after each sent reply; backing out without sending keeps the draft.
    var session by rememberSaveable(messageId) { mutableIntStateOf(0) }
    val tabletop = LocalFoldPosture.current is FoldPosture.Tabletop
    val detail = viewModel(key = "detail:$messageId") { detailViewModel() }
    if (!replying) {
        DetailRoute(detail, onReply = { replying = true })
        return
    }
    val reply = viewModel(key = "reply:$messageId:$session") { replyViewModel() }
    val close = {
        if (reply.state.value.sent) session++
        replying = false
    }
    if (tabletop) {
        DetailRoute(detail, onReply = null, belowHinge = { ReplyRoute(reply, onClose = close, compact = true) })
    } else {
        ReplyRoute(reply, onClose = close)
    }
}
