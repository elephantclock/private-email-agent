package tech.elephantclock.privatemail.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hinge bounds (dp from the window's top) → where a pane splits. Nothing may sit under the hinge. */
class FlexSplitTest {
    @Test
    fun flatAndBookPosturesDoNotSplit() {
        assertNull(flexSplitOf(FoldPosture.Flat, 0f, 750f))
        assertNull(flexSplitOf(FoldPosture.Book(416f, 416f), 0f, 750f))
    }

    @Test
    fun zeroWidthFoldSplitsAtTheFold() {
        assertEquals(FlexSplit(375f, 0f), flexSplitOf(FoldPosture.Tabletop(375f, 375f), 0f, 750f))
    }

    @Test
    fun occludingHingeIsKeptClear() {
        assertEquals(FlexSplit(360f, 30f), flexSplitOf(FoldPosture.Tabletop(360f, 390f), 0f, 750f))
    }

    @Test
    fun paneBelowABannerSplitsAtTheSameWindowLine() {
        // A 48 dp banner above the pane: the pane's top half is 48 dp shorter.
        assertEquals(FlexSplit(312f, 30f), flexSplitOf(FoldPosture.Tabletop(360f, 390f), 48f, 702f))
    }

    @Test
    fun paneStartingInsideTheHingeKeepsOnlyTheRest() {
        assertEquals(FlexSplit(0f, 20f), flexSplitOf(FoldPosture.Tabletop(360f, 390f), 370f, 380f))
    }

    @Test
    fun hingeOutsideThePaneDoesNotSplit() {
        assertNull(flexSplitOf(FoldPosture.Tabletop(360f, 390f), 400f, 350f))
        assertNull(flexSplitOf(FoldPosture.Tabletop(360f, 390f), 0f, 300f))
    }

    @Test
    fun windowInfoFromAHalfOpenedFoldSplitsWhereTheFeatureSays() {
        // FoldingFeature bounds in px (density 2): hinge 720..780 px → 360..390 dp.
        val posture = FoldPosture.Tabletop(720f / 2f, 780f / 2f)
        assertEquals(FlexSplit(360f, 30f), flexSplitOf(posture, 0f, 750f))
    }
}
