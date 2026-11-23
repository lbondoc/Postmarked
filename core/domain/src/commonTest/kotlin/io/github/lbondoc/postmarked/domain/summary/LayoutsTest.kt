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
}
