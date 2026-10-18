// Named Posture.kt as in the plan; the type is FoldPosture to avoid Material 3 Adaptive's Posture.
@file:Suppress("ktlint:standard:filename")

package tech.elephantclock.privatemail.feature.shell

import androidx.compose.runtime.Immutable
import androidx.window.layout.FoldingFeature

/**
 * How the device is folded, from WindowManager's [FoldingFeature]. Named `FoldPosture` so it
 * doesn't clash with Material 3 Adaptive's own `Posture`. Hinge edges are in dp from the
 * window's top (tabletop) or start (book), so layouts can split around the hinge.
 */
@Immutable
sealed interface FoldPosture {
    /** Phone, cover screen, or the inner screen opened flat. */
    data object Flat : FoldPosture

    /** Half-open with a horizontal hinge (Flex mode): read above, act below. */
    data class Tabletop(
        val hingeTopDp: Float,
        val hingeBottomDp: Float,
    ) : FoldPosture

    /** Half-open with a vertical hinge, held like a book. */
    data class Book(
        val hingeStartDp: Float,
        val hingeEndDp: Float,
    ) : FoldPosture
}

/**
 * Maps WindowManager's folding features to a [FoldPosture]. [density] converts the feature's
 * pixel bounds to dp. Only a half-opened fold changes the layout: the Fold7's inner screen
 * opened flat has no separating hinge, so it stays [FoldPosture.Flat].
 */
fun foldPostureOf(
    features: List<FoldingFeature>,
    density: Float,
): FoldPosture {
    val fold = features.firstOrNull { it.state == FoldingFeature.State.HALF_OPENED } ?: return FoldPosture.Flat
    val bounds = fold.bounds
    return if (fold.orientation == FoldingFeature.Orientation.HORIZONTAL) {
        FoldPosture.Tabletop(bounds.top / density, bounds.bottom / density)
    } else {
        FoldPosture.Book(bounds.left / density, bounds.right / density)
    }
}
