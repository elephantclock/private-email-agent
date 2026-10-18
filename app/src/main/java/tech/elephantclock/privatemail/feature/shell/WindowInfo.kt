package tech.elephantclock.privatemail.feature.shell

import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.collectFoldingFeaturesAsState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The window the shell lays out in: size in dp plus fold posture. A plain value, so tests and
 * screenshots can pass any size or posture (Robolectric can't produce a real FoldingFeature).
 */
@Immutable
data class WindowInfo(
    val widthDp: Int,
    val heightDp: Int,
    val posture: FoldPosture = FoldPosture.Flat,
) {
    val isCompactWidth: Boolean get() = widthDp < MEDIUM_WIDTH_DP
    val isPortrait: Boolean get() = heightDp >= widthDp
    val isTabletop: Boolean get() = posture is FoldPosture.Tabletop

    /**
     * Cover screen: bottom bar. Inner screen: bottom bar in portrait and in Flex mode (the
     * mockups' 08 and 10), a rail in landscape (09).
     */
    val navigationType: NavigationSuiteType
        get() =
            when {
                isCompactWidth -> NavigationSuiteType.ShortNavigationBarCompact
                isTabletop || isPortrait -> NavigationSuiteType.ShortNavigationBarMedium
                else -> NavigationSuiteType.WideNavigationRailCollapsed
            }

    /** Material 3 Adaptive's view of this window, for the pane scaffold directive (hinges in px). */
    fun toAdaptiveInfo(density: Float): WindowAdaptiveInfo {
        val hinges =
            when (posture) {
                FoldPosture.Flat -> {
                    emptyList()
                }

                is FoldPosture.Tabletop -> {
                    listOf(
                        HingeInfo(
                            bounds = Rect(0f, posture.hingeTopDp, widthDp.toFloat(), posture.hingeBottomDp).px(density),
                            isFlat = false,
                            isVertical = false,
                            isSeparating = true,
                            isOccluding = posture.hingeBottomDp > posture.hingeTopDp,
                        ),
                    )
                }

                is FoldPosture.Book -> {
                    listOf(
                        HingeInfo(
                            bounds = Rect(posture.hingeStartDp, 0f, posture.hingeEndDp, heightDp.toFloat()).px(density),
                            isFlat = false,
                            isVertical = true,
                            isSeparating = true,
                            isOccluding = posture.hingeEndDp > posture.hingeStartDp,
                        ),
                    )
                }
            }
        return WindowAdaptiveInfo(
            WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp),
            Posture(isTabletop = isTabletop, hingeList = hinges),
        )
    }

    companion object {
        const val MEDIUM_WIDTH_DP = 600
    }
}

private fun Rect.px(density: Float) = Rect(left * density, top * density, right * density, bottom * density)

/** The current window, updated on fold, unfold, rotation and resize. */
@Composable
fun rememberWindowInfo(): WindowInfo {
    val density = LocalDensity.current
    val size = LocalWindowInfo.current.containerSize
    val features = collectFoldingFeaturesAsState().value
    return remember(size, features, density) {
        WindowInfo(
            widthDp = (size.width / density.density).toInt(),
            heightDp = (size.height / density.density).toInt(),
            posture = foldPostureOf(features, density.density),
        )
    }
}
