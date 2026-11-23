package io.github.lbondoc.postmarked.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShotsTest {
    /** A 9 x 8 picture with brightness from a formula of (column, row). */
    private fun picture(brightness: (Int, Int) -> Int) = IntArray(72) { brightness(it % 9, it / 9) }

    @Test
    fun brightnessFallingToTheRightSetsEveryBit() =
        assertEquals(-1L, differenceHash(picture { col, _ -> 255 - col * 20 }))

    @Test
    fun brightnessRisingToTheRightSetsNone() =
        assertEquals(0L, differenceHash(picture { col, _ -> col * 20 }))

    @Test
    fun theFirstRowIsTheHighBits() {
        // Only row 0 falls to the right.
        val hash = differenceHash(picture { col, row -> if (row == 0) 255 - col else col })
        assertEquals(0xFF00000000000000uL.toLong(), hash)
    }

    @Test
    fun aSlightlyBrighterCopyKeepsItsFingerprint() {
        val scene = picture { col, row -> (col * 37 + row * 91) % 200 }
        val brighter = IntArray(72) { scene[it] + 30 }
        assertEquals(0, differingBits(differenceHash(scene), differenceHash(brighter)))
    }

    @Test
    fun differenceHashNeedsExactly72Values() {
        assertFailsWith<IllegalArgumentException> { differenceHash(IntArray(64)) }
    }

    @Test
    fun countsDifferingBits() {
        assertEquals(0, differingBits(5, 5))
        assertEquals(64, differingBits(0, -1))
        assertEquals(2, differingBits(0b1010, 0b0000))
    }

    @Test
    fun sharpEdgesScoreHigherThanSoftOnes() {
        val w = 20
        val h = 20
        val hardEdge = IntArray(w * h) { if (it % w < w / 2) 0 else 255 }
        val softEdge = IntArray(w * h) { (it % w) * 255 / (w - 1) }
        val flat = IntArray(w * h) { 128 }
        assertTrue(sharpness(hardEdge, w, h) > sharpness(softEdge, w, h))
        assertTrue(sharpness(softEdge, w, h) >= sharpness(flat, w, h))
        assertEquals(0.0, sharpness(flat, w, h))
        assertEquals(0.0, sharpness(IntArray(4), 2, 2), "too small to measure")
    }

    private val look = 0x0F0F0F0F0F0F0F0FL
    private val lookAlike = look xor 0b111 // 3 bits off
    private val other = look.inv()

    @Test
    fun aBurstBecomesOneGroupWithTheSharpestFirst() {
        val groups = groupSimilar(
            listOf(
                Shot("a", 0, look, 10.0),
                Shot("b", 400, lookAlike, 50.0),
                Shot("c", 800, look, 20.0),
            )
        )
        assertEquals(listOf(listOf("b", "c", "a")), groups.map { g -> g.map { it.id } })
    }

    @Test
    fun differentPicturesOrMomentsStaySeparate() {
        val groups = groupSimilar(
            listOf(
                Shot("a", 0, look, 1.0),
                Shot("different view", 1_000, other, 1.0),
                Shot("same view much later", 60_000, look, 1.0),
            )
        )
        assertEquals(3, groups.size)
    }

    @Test
    fun aLongBurstChainsPhotoToPhoto() {
        // Each photo is within 10 s of the previous one, though the last is 24 s after the first.
        val groups = groupSimilar((0..3).map { Shot("s$it", it * 8_000L, look, it.toDouble()) })
        assertEquals(1, groups.size)
    }

    @Test
    fun photosWithoutAFingerprintAreNeverGrouped() {
        val groups = groupSimilar(listOf(Shot("a", 0, null, null), Shot("b", 100, null, null), Shot("c", 200, look, null)))
        assertEquals(3, groups.size)
    }

    @Test
    fun orderComesFromTime() {
        val groups = groupSimilar(listOf(Shot("later", 5_000, other, 1.0), Shot("first", 0, look, 1.0)))
        assertEquals(listOf("first", "later"), groups.map { it.single().id })
    }

    @Test
    fun aTieInSharpnessKeepsTheEarlierShot() {
        val groups = groupSimilar(listOf(Shot("first", 0, look, null), Shot("second", 100, look, null)))
        assertEquals(listOf("first", "second"), groups.single().map { it.id })
    }
}
