package io.github.lbondoc.postmarked.domain.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonizeTest {
    private fun palette(vararg rgb: Int) = rgb.map { Swatch(Oklab.fromRgb(it), 1.0 / rgb.size) }
    private fun channels(rgb: Int) = Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private val coolPhoto = palette(0x6E8FA8, 0x2F4A5C, 0xC9D6DF) // blue-grey seaside
    private val warmTarget = palette(0xE0A458, 0x8C4A2F, 0xF4E1C1) // golden-hour road trip

    @Test
    fun nothingChangesAtZeroIntensity() {
        val grade = harmonize(coolPhoto, warmTarget, intensity = 0.0)
        assertEquals(Grade.IDENTITY, grade)
        for (rgb in listOf(0x6E8FA8, 0xFFFFFF, 0x000000)) assertEquals(rgb, grade.apply(rgb))
    }

    @Test
    fun aCoolPhotoWarmsTowardAWarmTarget() {
        val grade = harmonize(coolPhoto, warmTarget, intensity = 1.0)
        val (r0, _, b0) = channels(0x808080)
        val (r1, _, b1) = channels(grade.apply(0x808080))
        assertTrue(r1 - r0 > 5, "mid grey gains red: $r0 -> $r1")
        assertTrue(b0 - b1 > 5, "and loses blue: $b0 -> $b1")
    }

    @Test
    fun aMutedPhotoGetsMoreColourfulTowardAVividTarget() {
        val muted = palette(0x8A8F84, 0x6F746B, 0xA8ABA2)
        val vivid = palette(0xE63946, 0xF4A261, 0x2A9D8F)
        val before = Oklab.fromRgb(0x9A8C70)
        val after = Oklab.fromRgb(harmonize(muted, vivid, 1.0).apply(0x9A8C70))
        assertTrue(after.chroma > before.chroma, "${before.chroma} -> ${after.chroma}")
    }

    @Test
    fun halfIntensityIsHalfwayAndEverythingStaysInRange() {
        val full = harmonize(coolPhoto, warmTarget, 1.0)
        val half = harmonize(coolPhoto, warmTarget, 0.5)
        val (r0, _, _) = channels(0x808080)
        val (rFull, _, _) = channels(full.apply(0x808080))
        val (rHalf, _, _) = channels(half.apply(0x808080))
        assertTrue(rHalf in (r0 + 1) until rFull, "$r0 < $rHalf < $rFull")
        for (rgb in listOf(0xFFFFFF, 0x000000, 0xFF0000, 0x00FFFF)) full.apply(rgb) // clamps, never throws
    }

    @Test
    fun aMatchingPhotoBarelyChanges() {
        val grade = harmonize(warmTarget, warmTarget, 1.0)
        val (r0, g0, b0) = channels(0xC08850)
        val (r1, g1, b1) = channels(grade.apply(0xC08850))
        assertTrue(maxOf(kotlin.math.abs(r1 - r0), kotlin.math.abs(g1 - g0), kotlin.math.abs(b1 - b0)) <= 12, "only the toning shows")
    }

    @Test
    fun theColourMatrixMatchesApply() {
        val grade = harmonize(coolPhoto, warmTarget, 0.8)
        val f = grade.toColorMatrix()
        val (r, g, b) = channels(0x5A7A3C)
        // What a GPU colour-matrix filter computes, channel by channel, on 0-255 values.
        val red = (f[0] * r + f[1] * g + f[2] * b + f[4]).toInt().coerceIn(0, 255)
        val (expected, _, _) = channels(grade.apply(0x5A7A3C))
        assertTrue(kotlin.math.abs(red - expected) <= 1, "$red vs $expected")
        assertEquals(1f, f[18], "alpha is kept")
    }

    @Test
    fun emptyPalettesMeanNoChange() {
        assertEquals(Grade.IDENTITY, harmonize(emptyList(), warmTarget, 1.0))
        assertEquals(Grade.IDENTITY, harmonize(coolPhoto, emptyList(), 1.0))
    }
}
