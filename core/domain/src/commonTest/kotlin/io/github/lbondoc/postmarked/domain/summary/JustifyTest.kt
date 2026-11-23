package io.github.lbondoc.postmarked.domain.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JustifyTest {
    private val w = 1000.0
    private val g = 8.0
    private val target = 300.0

    private fun rowWidth(aspects: List<Double>, row: JustifiedRow) =
        (row.start until row.end).sumOf { aspects[it] * row.height } + g * (row.end - row.start - 1)

    @Test
    fun eachRowExactlyFillsTheWidth() {
        val aspects = listOf(1.5, 0.75, 1.33, 1.78, 1.0, 0.66, 1.5, 1.5, 2.0)
        val rows = justify(aspects, w, g, target)
        assertEquals((0 until aspects.size).toList(), rows.flatMap { (it.start until it.end).toList() }, "every photo once, in order")
        for (row in rows) assertEquals(w, rowWidth(aspects, row), 1e-9, "$row")
        // Rows come out near the target height (the last one has whatever is left).
        for (row in rows.dropLast(1)) assertTrue(row.height in target * 0.5..target * 1.6, "$row")
    }

    @Test
    fun aPortraitStaysPortrait() {
        val aspects = listOf(1.5, 0.66, 1.5)
        val row = justify(aspects, w, g, target).first { 1 in it.start until it.end }
        val photoW = aspects[1] * row.height
        assertTrue(photoW < row.height, "portrait: $photoW wide × ${row.height} tall")
        assertEquals(0.66, photoW / row.height, 1e-9)
    }

    @Test
    fun aVeryWidePhotoGetsARowToItself() {
        val aspects = listOf(1.5, 1.5, 1.5, 6.0, 1.5, 1.5)
        val rows = justify(aspects, w, g, target)
        val wide = rows.single { 3 in it.start until it.end }
        assertEquals(JustifiedRow(3, 4, w / 6.0), wide)
        assertEquals(w, rowWidth(aspects, wide), 1e-9)
    }

    @Test
    fun justifiedRowsLayoutReflowsByPhotoShape() {
        val portraits = Layout.JustifiedRows.arrange(List(6) { 0.75 })
        val landscapes = Layout.JustifiedRows.arrange(List(6) { 1.78 })
        assertTrue(portraits.aspect < landscapes.aspect, "portraits make a taller canvas")
        for (s in portraits.slots) assertEquals(0.75, s.w * portraits.aspect / s.h, 1e-9) // each slot keeps its photo's shape
        for (s in portraits.slots + landscapes.slots) assertTrue(s.x >= -1e-9 && s.x + s.w <= 1 + 1e-9 && s.y + s.h <= 1 + 1e-9)
    }

    @Test
    fun adaptiveLayoutsFitThePhotoAndStrictGridsDont() {
        val slot = Layout.Filmstrip.arrange(listOf(0.75)).slots.first()
        assertEquals(0.75, slot.w * Layout.Filmstrip.aspect / slot.h, 1e-9, "a portrait in the filmstrip keeps its shape")
        assertEquals(Layout.Grid3x3.slots, Layout.Grid3x3.arrange(List(9) { 0.75 }).slots, "the profile grid keeps square tiles")
        assertEquals(Layout.Filmstrip.slots[1], Layout.Filmstrip.arrange(listOf(0.75, null)).slots[1], "unknown shape: unchanged")
    }
}
