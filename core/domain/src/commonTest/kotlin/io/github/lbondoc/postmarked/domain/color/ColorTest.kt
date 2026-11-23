package io.github.lbondoc.postmarked.domain.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorTest {
    private val red = 0xFF0000
    private val yellow = 0xF2C230
    private val sky = 0x4A90D9
    private val grass = 0x3F8F3A
    private val white = 0xFFFFFF
    private val black = 0x000000

    private fun close(expected: Double, actual: Double, tolerance: Double = 0.002) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    // ----- OKLab -----

    @Test
    fun knownColoursMatchPublishedOklabValues() {
        // Reference values from Björn Ottosson's OKLab definition.
        val w = Oklab.fromRgb(white)
        close(1.0, w.l); close(0.0, w.a); close(0.0, w.b)
        close(0.0, Oklab.fromRgb(black).l)
        val r = Oklab.fromRgb(red)
        close(0.6279, r.l); close(0.2249, r.a); close(0.1258, r.b)
        close(29.2, r.hue, 0.2)
    }

    @Test
    fun convertingThereAndBackKeepsTheColour() {
        for (rgb in listOf(red, yellow, sky, grass, white, black, 0x123456, 0x808080)) {
            assertEquals(rgb, Oklab.fromRgb(rgb).toRgb(), rgbToHex(rgb))
        }
    }

    @Test
    fun greysHaveNoChroma() {
        assertTrue(Oklab.fromRgb(0x808080).chroma < 0.001)
        assertTrue(Oklab.fromRgb(sky).chroma > NEUTRAL_CHROMA)
    }

    @Test
    fun hexRoundTrips() {
        assertEquals("#F2C230", rgbToHex(yellow))
        assertEquals(yellow, hexToRgb("#F2C230"))
        assertEquals("#000000", rgbToHex(0xFF000000.toInt()), "alpha is dropped")
    }

    @Test
    fun hueDistanceGoesTheShortWayRound() {
        assertEquals(20.0, hueDistance(350.0, 10.0))
        assertEquals(180.0, hueDistance(0.0, 180.0))
        assertEquals(0.0, hueDistance(90.0, 90.0))
    }

    // ----- Palette extraction -----

    /** A picture that is [parts] of each colour, as ARGB pixels. */
    private fun picture(vararg parts: Pair<Int, Int>): IntArray =
        parts.flatMap { (rgb, count) -> List(count) { (0xFF shl 24) or rgb } }.toIntArray()

    @Test
    fun aTwoColourPictureHasThoseTwoColoursInProportion() {
        val p = palette(picture(sky to 700, yellow to 300), k = 5)
        assertEquals(2, p.size, "no more swatches than colours")
        assertEquals(sky, p[0].rgb)
        assertEquals(yellow, p[1].rgb)
        close(0.7, p[0].weight, 0.01)
        close(0.3, p[1].weight, 0.01)
    }

    @Test
    fun swatchesAreLargestFirstAndAddUpToOne() {
        val p = palette(picture(sky to 100, yellow to 500, grass to 250, red to 150, white to 1000))
        assertEquals(listOf(white, yellow, grass, red, sky), p.map { it.rgb })
        close(1.0, p.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun noisyColoursClusterAroundTheirCentre() {
        // Yellows that wobble a little, as a real sunflower field does, are one swatch.
        val pixels = IntArray(2000) { i ->
            val wobble = (i % 7) - 3
            (0xFF shl 24) or (((0xF2 + wobble) shl 16) or ((0xC2 + wobble) shl 8) or (0x30 + wobble))
        }
        val p = palette(pixels, k = 1)
        assertTrue(Oklab.fromRgb(yellow).distanceTo(p.single().color) < 0.01)
    }

    @Test
    fun theSamePictureAlwaysGivesTheSamePalette() {
        val pixels = IntArray(10_000) { i -> (0xFF shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        assertEquals(palette(pixels), palette(pixels))
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val pixels = picture(sky to 100) + IntArray(900) { 0x00FF0000 }
        assertEquals(listOf(sky), palette(pixels).map { it.rgb })
    }

    @Test
    fun anEmptyPictureHasNoPalette() = assertEquals(emptyList(), palette(IntArray(0)))

    @Test
    fun tripPaletteWeighsEachPhotoEqually() {
        val photos = listOf(
            palette(picture(yellow to 1000)),
            palette(picture(yellow to 1000)),
            palette(picture(sky to 1000)),
        )
        val trip = mergePalettes(photos, k = 2)
        assertEquals(listOf(yellow, sky), trip.map { it.rgb })
        close(2.0 / 3, trip[0].weight, 1e-9)
    }

    // ----- Choosing photos by colour -----

    private val sunflowers = palette(picture(yellow to 600, grass to 300, sky to 100))
    private val beach = palette(picture(sky to 700, 0xE8D8B0 to 300))
    private val snow = palette(picture(white to 800, 0x9AA0A6 to 200))

    @Test
    fun shareCountsTheColourFamily() {
        close(0.6, share(sunflowers, Oklab.fromRgb(0xE0B020)), 0.01) // a different yellow still counts
        close(0.0, share(beach, Oklab.fromRgb(yellow)), 0.01)
        close(0.8, share(snow, Oklab.fromRgb(0xF4F4F4)), 0.01) // white is matched by lightness
    }

    @Test
    fun dominantColourSkipsGreys() {
        assertEquals(yellow, dominantColour(sunflowers)!!.toRgb())
        assertNull(dominantColour(palette(picture(white to 10, black to 10))))
    }

    @Test
    fun rainbowGoesRedYellowGreenBlueThenGreys() {
        val palettes = listOf(beach, snow, palette(picture(red to 10)), sunflowers, palette(picture(grass to 10)))
        assertEquals(listOf(2, 3, 4, 0, 1), rainbowOrder(palettes))
    }

    @Test
    fun gradientFlowsFromTheFirstColourToTheLast() {
        val yellowPhoto = palette(picture(yellow to 10))
        val bluePhoto = palette(picture(sky to 10))
        val greenPhoto = palette(picture(grass to 10))
        val order = gradientOrder(listOf(bluePhoto, greenPhoto, yellowPhoto), listOf(Oklab.fromRgb(yellow), Oklab.fromRgb(sky)), slots = 3)
        assertEquals(listOf(2, 1, 0), order, "yellow first, blue last, green between")
        assertEquals(2, gradientOrder(listOf(bluePhoto, greenPhoto), listOf(Oklab.fromRgb(sky)), slots = 9).size, "stops when photos run out")
    }

    // ----- Text colours -----

    @Test
    fun contrastMatchesWcag() {
        close(21.0, contrast(black, white), 0.01)
        close(1.0, contrast(sky, sky), 1e-9)
        close(4.48, contrast(0x777777, white), 0.01) // the classic "just fails AA" grey
    }

    @Test
    fun textIsAlwaysReadable() {
        for (background in listOf(yellow, sky, grass, red, white, black, 0x777777)) {
            assertTrue(contrast(readableOn(background), background) >= 4.5, rgbToHex(background))
        }
        assertEquals(0x1F1B16, readableOn(yellow), "dark ink on yellow")
        assertEquals(0x1F5FA6, readableOn(white, preferred = listOf(yellow, 0x1F5FA6)), "yellow is unreadable on white; the dark blue is fine")
    }

    @Test
    fun coloursHavePlainNames() {
        val names = mapOf(
            red to "Red", yellow to "Yellow", sky to "Blue", grass to "Green", white to "White", black to "Black",
            0x808080 to "Grey", 0x8B5A2B to "Brown", 0xF28C28 to "Orange", 0x2A9D8F to "Teal", 0x7B4FA0 to "Purple", 0xE86A92 to "Pink",
        )
        for ((rgb, name) in names) assertEquals(name, colourName(Oklab.fromRgb(rgb)), rgbToHex(rgb))
    }
}
