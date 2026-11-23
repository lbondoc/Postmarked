package io.github.lbondoc.postmarked.domain.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutsTest {
    private val eps = 1e-9

    @Test
    fun everySlotIsOnTheCanvas() {
        for (layout in Layout.entries) for ((i, s) in layout.slots.withIndex()) {
            assertTrue(s.x >= -eps && s.y >= -eps && s.x + s.w <= 1 + eps && s.y + s.h <= 1 + eps, "${layout.name} slot $i: $s")
            assertTrue(s.w > 0 && s.h > 0)
        }
    }

    @Test
    fun textSlotsExist() {
        for (layout in Layout.entries) assertTrue(layout.textSlots.all { it in layout.slots.indices }, layout.name)
    }

    @Test
    fun straightLayoutsNeverOverlap() {
        for (layout in Layout.entries.filter { l -> l.slots.all { it.rotation == 0.0 } }) {
            for (a in layout.slots.indices) for (b in a + 1 until layout.slots.size) {
                val p = layout.slots[a]
                val q = layout.slots[b]
                val apart = p.x + p.w <= q.x + eps || q.x + q.w <= p.x + eps || p.y + p.h <= q.y + eps || q.y + q.h <= p.y + eps
                assertTrue(apart, "${layout.name}: slots $a and $b overlap")
            }
        }
    }

    @Test
    fun gridSquaresAreSquareOnTheCanvas() {
        // On the 2 x 3 canvas (2:3), a slot's real width and height in pixels should match.
        for (layout in listOf(Layout.Grid3x3, Layout.Grid2x3)) {
            val s = layout.slots.first()
            assertEquals(s.w * layout.aspect, s.h, 1e-9, layout.name)
        }
    }

    @Test
    fun instagramGridHasNineTiles() = assertEquals(9, Layout.Grid3x3.slots.size)

    @Test
    fun theNewLayoutsHaveTheirPhotoCounts() {
        assertEquals(2, Layout.TwoUp.slots.size)
        assertEquals(4, Layout.FourUp.slots.size)
        assertEquals(listOf(1, 1), listOf(Layout.Panorama2, Layout.Panorama3).map { it.slots.size })
        assertEquals(listOf(2, 3), listOf(Layout.Panorama2, Layout.Panorama3).map { it.slides })
    }

    @Test
    fun panoramaSlicesLineUpEdgeToEdge() {
        for (layout in Layout.entries.filter { it.slides > 1 }) {
            val width = 1080.0 * layout.slides // each slide 1080 px wide
            val slices = panoramaSlices(layout.slides, width)
            assertEquals(layout.slides, slices.size)
            assertEquals(0.0, slices.first().first)
            assertEquals(width, slices.last().second)
            for (i in 0 until slices.size - 1) assertEquals(slices[i].second, slices[i + 1].first, "${layout.name}: slides ${i + 1} and ${i + 2} meet")
            for ((left, right) in slices) assertEquals(1080.0, right - left, 1e-9)
            // The canvas is exactly that many 4:5 slides wide, so no slide gets a sliver of border.
            assertEquals(layout.slides * 4.0 / 5, layout.aspect, 1e-9)
        }
        assertEquals(listOf(0.0 to 100.0), panoramaSlices(1, 100.0))
    }
}
