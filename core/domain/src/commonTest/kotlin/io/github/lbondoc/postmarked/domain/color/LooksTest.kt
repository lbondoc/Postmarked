package io.github.lbondoc.postmarked.domain.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LooksTest {
    private fun channels(rgb: Int) = Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    private val sunflower = 0xF2C230
    private val sky = 0x4A90D9

    @Test
    fun plainLookChangesNothing() {
        assertTrue(Look().isPlain)
        assertTrue(Look(Filter.Golden, strength = 0.0).isPlain)
        for (rgb in listOf(sunflower, sky, 0x000000, 0xFFFFFF)) assertEquals(rgb, Look().grade().apply(rgb))
    }

    @Test
    fun blackAndWhiteHasNoColourLeft() {
        for (rgb in listOf(sunflower, sky, 0x3F8F3A)) {
            val (r, g, b) = channels(Look(Filter.BlackAndWhite).grade().apply(rgb))
            assertTrue(maxOf(r, g, b) - minOf(r, g, b) <= 1, "${rgb.toString(16)} -> $r $g $b")
        }
    }

    @Test
    fun contrastKeepsMidGreyAndSpreadsTheRest() {
        val more = Look(adjust = mapOf(Adjustment.Contrast to 1.0)).grade()
        assertEquals(0x808080, more.apply(0x808080) and 0xFEFEFE) // rounding may move it by one
        assertTrue(channels(more.apply(0xC0C0C0)).first > 0xC0)
        assertTrue(channels(more.apply(0x404040)).first < 0x40)
    }

    @Test
    fun warmthMovesRedUpAndBlueDown() {
        val (r, _, b) = channels(Look(adjust = mapOf(Adjustment.Warmth to 1.0)).grade().apply(0x808080))
        val (r2, _, b2) = channels(Look(adjust = mapOf(Adjustment.Warmth to -1.0)).grade().apply(0x808080))
        assertTrue(r > 0x80 && b < 0x80)
        assertTrue(r2 < 0x80 && b2 > 0x80)
    }

    @Test
    fun fadeLiftsBlack() {
        val (r, g, b) = channels(Look(adjust = mapOf(Adjustment.Fade to 1.0)).grade().apply(0x000000))
        assertTrue(r > 0 && r == g && g == b)
    }

    @Test
    fun halfStrengthIsHalfway() {
        val full = channels(Look(Filter.Golden).grade().apply(sky)).first
        val half = channels(Look(Filter.Golden, strength = 0.5).grade().apply(sky)).first
        assertTrue(kotlin.math.abs(half - (full + 0x4A) / 2) <= 1)
    }

    @Test
    fun thenAppliesInOrder() {
        // Brighten then grey-out differs from grey-out then brighten only by rounding: both affine.
        val a = brightness(1.2) then saturation(0.0)
        val step = saturation(0.0).apply(brightness(1.2).apply(sky))
        assertTrue(kotlin.math.abs(channels(a.apply(sky)).first - channels(step).first) <= 1)
        // Contrast then lift is not lift then contrast: order matters, and then keeps it.
        val lift = diagonal(0.0)
        assertTrue((contrast(2.0) then lift).apply(0x808080) != (lift then contrast(2.0)).apply(0x808080))
    }

    private fun diagonal(offset: Double) = Grade(Grade.IDENTITY.m, listOf(offset + 0.1, offset + 0.1, offset + 0.1))
}
