package io.github.lbondoc.postmarked.domain.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripHuesTest {
    private fun photo(vararg swatches: Pair<Int, Double>) = swatches.map { (rgb, w) -> Swatch(Oklab.fromRgb(rgb), w) }

    // A night drive: asphalt, black shadow, a navy night sky (colourful but L 0.25) and dark dirt (L 0.26).
    private val road = photo(0x45474A to 0.35, 0x141414 to 0.25, 0x14203A to 0.2, 0x33200F to 0.1, 0x262626 to 0.1)
    private val sunset = photo(0xF08A30 to 0.35, 0xC8502A to 0.2, 0x6A4A7A to 0.2, 0x1A1414 to 0.25)
    private val sky = photo(0x4A90D9 to 0.6, 0xA8CCEE to 0.3, 0xF2F2F2 to 0.1)
    private val trip = List(8) { road } + listOf(sunset, sky)

    @Test
    fun eightDarkRoadPhotosDontDrownTheSunsetAndTheSky() {
        val hues = tripHues(trip)
        val names = hues.map { colourName(it.color) }
        assertTrue("Orange" in names, "$names")
        assertTrue("Blue" in names, "$names")
        assertTrue(hues.all { it.color.l >= 0.3 }, "no muddy dark swatch: ${hues.map { it.color.l }}")
        assertTrue(hues.count { it.color.chroma < NEUTRAL_CHROMA } <= 1, "at most one neutral: $names")
    }

    @Test
    fun eachHueIsARealSwatchFromAPhoto() {
        val all = trip.flatten().map { it.color }.toSet()
        assertTrue(tripHues(trip).all { it.color in all })
    }

    @Test
    fun strongestSectorFirstAndTheNeutralLast() {
        val hues = tripHues(trip)
        // The sky's blue (weight 0.6 × chroma 0.13) outscores the sunset's orange (0.35 × 0.16).
        assertEquals("Blue", colourName(hues.first().color))
        assertTrue(hues.dropLast(1).all { it.color.chroma >= NEUTRAL_CHROMA })
        assertEquals("Grey", colourName(hues.last().color)) // the asphalt covers more than any white or black
    }

    @Test
    fun eachPhotoCountsEquallyWhateverItsWeightsAddUpTo() {
        val loud = photo(0xF08A30 to 10.0) // weights not normalised
        val quiet = photo(0x4A90D9 to 1.0)
        val hues = tripHues(listOf(loud, quiet))
        assertEquals(listOf(0.5, 0.5), hues.map { it.weight })
    }

    @Test
    fun atMostSixHuesAndFaintOnesAreDropped() {
        val wheel = (0 until 12).map { i -> photo(Oklab(0.7, 0.15 * kotlin.math.cos(i * 0.5236 + 0.26), 0.15 * kotlin.math.sin(i * 0.5236 + 0.26)).toRgb() to 1.0) }
        assertEquals(6, tripHues(wheel).size)
        val faint = photo(0xF08A30 to 0.999, 0x4A90D9 to 0.001) // blue scores under 2 % of orange
        assertEquals(listOf("Orange"), tripHues(listOf(faint)).map { colourName(it.color) })
    }

    @Test
    fun noPalettesNoColours() = assertEquals(emptyList(), tripHues(emptyList()))
}
