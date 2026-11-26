package io.github.lbondoc.postmarked.domain.summary

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportGeometryTest {
    @Test
    fun aSquareInAPortraitPostIsCentredTopToBottom() =
        assertEquals(Box(0.0, 135.0, 1080.0, 1080.0), fit(1.0, Box(0.0, 0.0, 1080.0, 1350.0)))

    @Test
    fun aTallLayoutInASquareIsCentredSideToSide() =
        assertEquals(Box(270.0, 0.0, 540.0, 1080.0), fit(0.5, Box(0.0, 0.0, 1080.0, 1080.0)))

    @Test
    fun fittingRespectsTheAreasOffset() =
        assertEquals(Box(50.0, 185.0, 980.0, 980.0), fit(1.0, Box(50.0, 60.0, 980.0, 1230.0)), "inside a border, still centred")

    @Test
    fun aLandscapePhotoInASquareTileLosesItsSides() =
        assertEquals(Box(500.0, 0.0, 3000.0, 3000.0), cropToFill(4000.0, 3000.0, 1.0))

    @Test
    fun aPortraitPhotoInALandscapeSlotLosesTopAndBottom() =
        assertEquals(Box(0.0, 1250.0, 3000.0, 1500.0), cropToFill(3000.0, 4000.0, 2.0))

    @Test
    fun theBottomRightTileIsPostedFirst() {
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1, 0), postingOrder(9))
    }

    @Test
    fun cropZoomsAroundTheChosenPointAndStopsAtTheEdges() {
        // A 4000 x 3000 photo in a square tile: the uncropped view is the middle 3000 x 3000.
        assertEquals(Box(500.0, 0.0, 3000.0, 3000.0), cropRegion(4000.0, 3000.0, 1.0, 0.5, 0.5, 1.0))
        // Twice as close, centred on the sun at (75%, 25%).
        assertEquals(Box(2250.0, 0.0, 1500.0, 1500.0), cropRegion(4000.0, 3000.0, 1.0, 0.75, 0.25, 2.0))
        // Panning past the left edge stops at it; zooming out past "fill" is not allowed.
        assertEquals(Box(0.0, 0.0, 3000.0, 3000.0), cropRegion(4000.0, 3000.0, 1.0, 0.0, 0.5, 0.5))
    }

    @Test
    fun theExportedCropMatchesTheChosenShape() {
        // A 4:3 photo cropped to 4:5 in an adaptive layout: its slot takes 4:5, so the exported crop is 4:5.
        for (layout in Layout.entries.filter { it.adaptive }) {
            val arranged = layout.arrange(listOf(0.8))
            val slot = arranged.slots[0]
            val slotAspect = slot.w * arranged.aspect / slot.h
            assertEquals(0.8, slotAspect, 1e-9, layout.name)
            val crop = cropRegion(4000.0, 3000.0, slotAspect, 0.5, 0.5, 1.0)
            assertEquals(0.8, crop.width / crop.height, 1e-9, layout.name)
        }
        // A strict grid keeps its square tiles whatever shape was chosen.
        val grid = Layout.Grid3x3.arrange(listOf(0.8))
        assertEquals(1.0, grid.slots[0].w * grid.aspect / grid.slots[0].h, 1e-9)
    }
}
