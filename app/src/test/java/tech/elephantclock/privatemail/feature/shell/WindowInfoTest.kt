package tech.elephantclock.privatemail.feature.shell

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowInfoTest {
    private val cover = WindowInfo(412, 960)
    private val innerPortrait = WindowInfo(750, 832)
    private val innerLandscape = WindowInfo(832, 750)
    private val flex = WindowInfo(832, 750, FoldPosture.Tabletop(375f, 375f))

    @Test
    fun navigationFollowsTheMockups() {
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, cover.navigationType)
        assertEquals(NavigationSuiteType.ShortNavigationBarMedium, innerPortrait.navigationType)
        assertEquals(NavigationSuiteType.WideNavigationRailCollapsed, innerLandscape.navigationType)
        assertEquals(NavigationSuiteType.ShortNavigationBarMedium, flex.navigationType)
    }

    @Test
    fun twoPanesOnTheInnerScreenOneOnTheCoverAndInFlexMode() {
        assertEquals(1, shellDirective(cover, 2.625f).maxHorizontalPartitions)
        assertEquals(2, shellDirective(innerPortrait, 2.625f).maxHorizontalPartitions)
        assertEquals(2, shellDirective(innerLandscape, 2.625f).maxHorizontalPartitions)
        assertEquals(1, shellDirective(flex, 2.625f).maxHorizontalPartitions)
    }

    @Test
    fun tabletopReachesMaterialAdaptive() {
        val info = flex.toAdaptiveInfo(2f)
        assertTrue(info.windowPosture.isTabletop)
        assertEquals(
            750f,
            info.windowPosture.hingeList
                .single()
                .bounds.top,
        )
    }
}
