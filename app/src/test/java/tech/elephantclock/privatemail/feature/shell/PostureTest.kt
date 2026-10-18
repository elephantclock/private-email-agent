package tech.elephantclock.privatemail.feature.shell

import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PostureTest {
    private fun fold(
        state: FoldingFeature.State,
        orientation: FoldingFeature.Orientation,
        bounds: Rect,
    ) = object : FoldingFeature {
        override val bounds = bounds
        override val isSeparating = state == FoldingFeature.State.HALF_OPENED
        override val occlusionType = FoldingFeature.OcclusionType.NONE
        override val orientation = orientation
        override val state = state
    }

    @Test
    fun noFeatureIsFlat() {
        assertEquals(FoldPosture.Flat, foldPostureOf(emptyList(), 2.625f))
    }

    @Test
    fun innerScreenOpenedFlatStaysFlat() {
        val flat = fold(FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL, Rect(984, 0, 984, 2160))
        assertEquals(FoldPosture.Flat, foldPostureOf(listOf(flat), 2.625f))
    }

    @Test
    fun halfOpenedHorizontalHingeIsTabletopInDp() {
        val f = fold(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL, Rect(0, 750, 1664, 780))
        assertEquals(FoldPosture.Tabletop(375f, 390f), foldPostureOf(listOf(f), 2f))
    }

    @Test
    fun halfOpenedVerticalHingeIsBook() {
        val f = fold(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL, Rect(832, 0, 832, 1500))
        assertEquals(FoldPosture.Book(416f, 416f), foldPostureOf(listOf(f), 2f))
    }
}
