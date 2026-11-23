package io.github.lbondoc.postmarked.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoomTest {
    private val page = Size(1000f, 2000f)

    @Test
    fun scaleIsClampedBetweenOneAndFive() {
        assertEquals(5f, ZoomState().transformed(20f, Offset.Zero, Offset.Zero, page).scale)
        assertEquals(1f, ZoomState(2f).transformed(0.1f, Offset.Zero, Offset.Zero, page).scale)
        assertEquals(3f, ZoomState().transformed(3f, Offset.Zero, Offset.Zero, page).scale)
    }

    @Test
    fun panIsClampedSoThePhotoNeverLeavesTheScreen() {
        // At 3×: |tx| ≤ (3 - 1) × 1000 / 2 = 1000, |ty| ≤ 2000.
        assertEquals(Offset(1000f, -2000f), clampPan(Offset(5000f, -9000f), 3f, page))
        assertEquals(Offset(300f, 400f), clampPan(Offset(300f, 400f), 3f, page))
        assertEquals(Offset.Zero, clampPan(Offset(50f, 50f), 1f, page)) // unzoomed, nothing can move
        val pinched = ZoomState(3f).transformed(1f, Offset(99_999f, 0f), Offset.Zero, page)
        assertEquals(Offset(1000f, 0f), pinched.pan)
    }

    @Test
    fun zoomingOutToOneRecentresThePhoto() {
        val zoomed = ZoomState(2f, Offset(400f, 300f))
        assertEquals(ZoomState(), zoomed.transformed(0.5f, Offset.Zero, Offset.Zero, page))
    }

    @Test
    fun aPinchKeepsThePointUnderTheFingers() {
        val centroid = Offset(200f, -300f)
        val z = ZoomState().transformed(2f, Offset.Zero, centroid, page)
        // Content point under the centroid before (scale 1, no pan) is the centroid itself; after: (p - t) / s.
        val content = (centroid - z.pan) / z.scale
        assertTrue((content - centroid).getDistance() < 0.01f, "$content")
    }

    @Test
    fun swipePansWhenZoomedAndChangesPhotoWhenNot() {
        assertNull(ZoomState().swipe(Offset(-300f, 0f), page), "at 1× the pager gets the swipe")
        val panned = ZoomState(2f).swipe(Offset(-300f, 0f), page)!!
        assertEquals(Offset(-300f, 0f), panned.pan)
        assertEquals(Offset(-500f, 0f), panned.swipe(Offset(-900f, 0f), page)!!.pan) // clamped at (2 - 1) × 1000 / 2
    }

    @Test
    fun doubleTapTogglesAroundTheTappedPoint() {
        val tapped = ZoomState().doubleTapped(Offset(100f, 200f), page)
        assertEquals(2.5f, tapped.scale)
        assertEquals(Offset(-150f, -300f), tapped.pan) // keeps (100, 200) under the finger
        assertEquals(ZoomState(), tapped.doubleTapped(Offset.Zero, page))
    }
}
