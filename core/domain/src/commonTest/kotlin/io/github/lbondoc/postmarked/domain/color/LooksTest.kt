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

    private val samples = listOf(sunflower, sky, 0x3F8F3A, 0xB5452F, 0x8A6F5A)
    private fun lab(rgb: Int) = Oklab.fromRgb(rgb)
    private fun after(filter: Filter, rgb: Int) = Look(filter).grade().apply(rgb)

    @Test
    fun monoHasEqualChannels() {
        for (rgb in samples) {
            val (r, g, b) = channels(after(Filter.Mono, rgb))
            assertTrue(r == g && g == b, "${rgb.toString(16)} -> $r $g $b")
        }
    }

    @Test
    fun vividRaisesChroma() {
        for (rgb in samples) assertTrue(lab(after(Filter.Vivid, rgb)).chroma > lab(rgb).chroma, rgb.toString(16))
    }

    @Test
    fun moodyLowersLightness() {
        for (rgb in samples + 0x808080) assertTrue(lab(after(Filter.Moody, rgb)).l < lab(rgb).l, rgb.toString(16))
    }

    @Test
    fun desertWarms() {
        // Warmer: red gains on blue, and in OKLab the colour moves toward yellow (b up).
        for (rgb in samples + 0x808080) {
            val (r0, _, b0) = channels(rgb)
            val (r, _, b) = channels(after(Filter.Desert, rgb))
            assertTrue(r - b > r0 - b0, "${rgb.toString(16)}: red-blue $r0-$b0 -> $r-$b")
            assertTrue(lab(after(Filter.Desert, rgb)).b > lab(rgb).b, rgb.toString(16))
        }
    }

    @Test
    fun nightDriveCoolsAndDarkens() {
        for (rgb in samples + 0x808080) {
            val (r0, _, b0) = channels(rgb)
            val (r, _, b) = channels(after(Filter.NightDrive, rgb))
            // Cooler means more blue relative to red; a ratio, because darkening shrinks the gap between them too.
            assertTrue((b + 1.0) / (r + 1) > (b0 + 1.0) / (r0 + 1), "${rgb.toString(16)}: blue/red $b0/$r0 -> $b/$r")
            assertTrue(lab(after(Filter.NightDrive, rgb)).l < lab(rgb).l, rgb.toString(16))
        }
    }
}
