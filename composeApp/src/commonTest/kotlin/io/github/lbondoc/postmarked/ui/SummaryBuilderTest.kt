package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.PaletteEntry
import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.summary.Layout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SummaryBuilderTest {
    private fun photo(id: String, vararg colours: Pair<String, Double>) = TripMedia(
        id = id, kind = MediaKind.Photo, contributor = "me", capturedAt = "2026-10-03T18:00:00Z",
        palette = colours.map { (c, w) -> PaletteEntry(c, w) },
    )

    private val yellow = "#F2C230"
    private val sky = "#4A90D9"
    private val grass = "#3F8F3A"
    private val red = "#D7263D"

    private val photos = listOf(
        photo("sunflowers", yellow to 0.7, grass to 0.3),
        photo("taxi", yellow to 0.4, "#333333" to 0.6),
        photo("ocean", sky to 0.9, "#FFFFFF" to 0.1),
        photo("forest", grass to 0.8, sky to 0.2),
        photo("barn", red to 0.6, sky to 0.4),
        photo("no palette yet"),
    )

    private fun ids(tiles: List<SummaryTile>) = tiles.map { it.media ?: it.text?.let { t -> "text:$t" } ?: it.bg?.let { "colour" } ?: "empty" }

    @Test
    fun oneColourPicksTheYellowestPhotosAndFillsTheRestWithColour() {
        val tiles = generateTiles(SummaryMode.Monochrome, Layout.Grid3x3, photos, listOf(Oklab.fromRgb(0xF2C230)), "Big Sur")
        assertEquals(
            listOf("sunflowers", "taxi", "colour", "colour", "text:Big Sur", "colour", "colour", "colour", "colour"),
            ids(tiles),
        )
        assertTrue(tiles.filter { it.media == null }.all { it.bg == yellow }, "text and filler tiles are the chosen colour")
    }

    @Test
    fun tripPaletteFlowsThroughTheColours() {
        val colours = listOf(Oklab.fromRgb(0xF2C230), Oklab.fromRgb(0x4A90D9))
        val tiles = generateTiles(SummaryMode.Palette, Layout.Hero, photos, colours, "Trip")
        assertEquals(listOf("sunflowers", "taxi", "forest", "ocean", "barn"), ids(tiles), "yellow, then green, toward blue")
    }

    @Test
    fun rainbowSpreadsAcrossTheHues() {
        val tiles = generateTiles(SummaryMode.Rainbow, Layout.Filmstrip, photos, listOf(Oklab.fromRgb(0xF2C230)), "Trip")
        assertEquals(listOf("barn", "sunflowers", "forest", "ocean"), ids(tiles), "red, yellow, green, blue: both ends included")
    }

    @Test
    fun photosWithoutAPaletteAreLeftOut() {
        val tiles = generateTiles(SummaryMode.Rainbow, Layout.Grid3x3, photos, listOf(Oklab.fromRgb(0xF2C230)), "T")
        assertTrue("no palette yet" !in ids(tiles))
    }

    @Test
    fun manualFillsPhotoSlotsInPickOrderAroundTextTiles() {
        val current = generateTiles(SummaryMode.Monochrome, Layout.Grid3x3, photos, listOf(Oklab.fromRgb(0xF2C230)), "Big Sur")
        val tiles = fillManually(Layout.Grid3x3, current, listOf("ocean", "barn", "forest"))
        assertEquals(listOf("ocean", "barn", "forest", "empty", "text:Big Sur", "empty", "empty", "empty", "empty"), ids(tiles))
        assertFailsWith<IllegalArgumentException> { generateTiles(SummaryMode.Manual, Layout.Grid3x3, photos, emptyList(), "") }
    }

    @Test
    fun swapping() {
        val tiles = listOf(SummaryTile(media = "a"), SummaryTile(text = "b"), SummaryTile(media = "c"))
        assertEquals(listOf("c", "text:b", "a"), ids(swapTiles(tiles, 0, 2)))
    }
}
