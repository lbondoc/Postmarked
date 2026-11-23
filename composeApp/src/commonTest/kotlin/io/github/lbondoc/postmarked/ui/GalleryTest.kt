package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.PaletteEntry
import io.github.lbondoc.postmarked.data.Profile
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.domain.color.COLOUR_WHEEL
import io.github.lbondoc.postmarked.domain.color.rgbToHex
import io.github.lbondoc.postmarked.domain.trip.TripArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class GalleryTest {
    private val maya = Profile("Maya")
    private val sam = Profile("Sam")
    private val look = 0x0F0F0F0F0F0F0F0FL

    // Times are UTC; utcOffsetMinutes = 0 keeps each item's day the UTC day, whatever the test machine's zone.
    private fun item(
        id: String, time: String, by: Profile = maya, kind: MediaKind = MediaKind.Photo,
        at: Pair<Double, Double>? = null, dhash: Long? = null, sharpness: Double? = null,
    ) = TripMedia(
        id = id, kind = kind, contributor = by.displayName, capturedAt = time, utcOffsetMinutes = 0,
        lat = at?.first, lng = at?.second, dhash = dhash, sharpness = sharpness, profile = by,
    )

    private val bigSur = 36.27 to -121.81
    private val joshuaTree = 34.13 to -116.31

    private val items = listOf(
        item("burst1", "2026-10-03T18:00:00Z", at = bigSur, dhash = look, sharpness = 5.0),
        item("burst2", "2026-10-03T18:00:01Z", at = bigSur, dhash = look, sharpness = 9.0),
        item("video", "2026-10-03T18:00:02Z", kind = MediaKind.Video, at = bigSur),
        item("sam", "2026-10-04T20:00:00Z", by = sam, at = joshuaTree),
        item("nowhere", "2026-10-04T21:00:00Z", by = sam),
    )

    private val places = mapOf(
        "Big Sur" to TripArea(listOf(Json.parseToJsonElement(
            """{"type": "Polygon", "coordinates": [[[-122, 36], [-121.5, 36], [-121.5, 36.5], [-122, 36.5], [-122, 36]]]}"""
        ).jsonObject)),
    )

    private fun ids(sections: List<Section>) = sections.map { s -> s.tiles.map { t -> t.all.map { it.id } } }

    @Test
    fun allViewFoldsABurstUnderItsSharpestShot() {
        val sections = gallerySections(items, items, GalleryView.All, LocalDate(2026, 10, 3))
        assertEquals(listOf(listOf(listOf("burst2", "burst1"), listOf("video"), listOf("sam"), listOf("nowhere"))), ids(sections))
        assertEquals(null, sections.single().title)
    }

    @Test
    fun daysAreNumberedFromTheTripStart() {
        val sections = gallerySections(items, items, GalleryView.Days, LocalDate(2026, 10, 3))
        assertEquals(listOf("Day 1 · Oct 3, 2026 · 3", "Day 2 · Oct 4, 2026 · 2"), sections.map { it.title })
    }

    @Test
    fun stopsComeFromTheRouteAndUnlocatedItemsGoLast() {
        val sections = gallerySections(items, items, GalleryView.Stops, LocalDate(2026, 10, 3))
        assertEquals(listOf("Stop 1 · 3", "Stop 2 · 1", "No location · 1"), sections.map { it.title })
    }

    @Test
    fun filteringKeepsStopNumbersFromTheWholeTrip() {
        val onlySam = filterMedia(items, GalleryFilter(people = setOf("Sam")), places)
        val sections = gallerySections(onlySam, items, GalleryView.Stops, LocalDate(2026, 10, 3))
        assertEquals(listOf("Stop 2 · 1", "No location · 1"), sections.map { it.title })
        // Once a stop's place name is known, it joins the number.
        val named = gallerySections(onlySam, items, GalleryView.Stops, LocalDate(2026, 10, 3), stopNames = mapOf(1 to "Joshua Tree"))
        assertEquals(listOf("Stop 2 · Joshua Tree · 1", "No location · 1"), named.map { it.title })
    }

    @Test
    fun eachChoiceCountsWithTheOtherGroupsButNotItsOwn() {
        val onlyMayaVideos = GalleryFilter(people = setOf("Maya"), kinds = setOf(MediaKind.Video))
        // Counting people ignores the people already chosen, but keeps "videos only".
        assertEquals(mapOf("Maya" to 1, "Sam" to 0), countEach(items, onlyMayaVideos, places, listOf("Maya", "Sam")) { copy(people = setOf(it)) })
        // Counting types keeps "Maya only".
        assertEquals(
            mapOf(MediaKind.Photo to 2, MediaKind.Video to 1),
            countEach(items, onlyMayaVideos, places, listOf(MediaKind.Photo, MediaKind.Video)) { copy(kinds = setOf(it)) },
        )
        assertEquals(2, onlyMayaVideos.count)
    }

    @Test
    fun colourFilterKeepsPhotosMostlyInThatColour() {
        val yellowish = item("sunflowers", "2026-10-03T19:00:00Z").copy(palette = listOf(PaletteEntry("#F2C230", 0.7), PaletteEntry("#3F8F3A", 0.3)))
        val bluish = item("ocean", "2026-10-03T20:00:00Z").copy(palette = listOf(PaletteEntry("#4A90D9", 0.9)))
        val all = listOf(yellowish, bluish, item("unknown", "2026-10-03T21:00:00Z"))
        assertEquals(listOf("sunflowers"), filterMedia(all, GalleryFilter(colours = setOf("#E0B020")), places).map { it.id })
        assertEquals(listOf("sunflowers", "ocean"), filterMedia(all, GalleryFilter(colours = setOf("#E0B020", "#3A7BC8")), places).map { it.id })
    }

    @Test
    fun aWheelColourFiltersToExactlyItsPhotosAndAnUnusedOneCountsZero() {
        val sunflowers = item("sunflowers", "2026-10-03T19:00:00Z").copy(palette = listOf(PaletteEntry("#F2C230", 0.7), PaletteEntry("#3F8F3A", 0.3)))
        val ocean = item("ocean", "2026-10-03T20:00:00Z").copy(palette = listOf(PaletteEntry("#4A90D9", 0.9), PaletteEntry("#F4F4F4", 0.1)))
        val snow = item("snow", "2026-10-03T21:00:00Z").copy(palette = listOf(PaletteEntry("#F4F4F4", 0.8), PaletteEntry("#4A90D9", 0.2)))
        val all = listOf(sunflowers, ocean, snow, item("unknown", "2026-10-03T22:00:00Z"))
        val wheel = COLOUR_WHEEL.associate { (name, rgb) -> name to rgbToHex(rgb) }
        val counts = countEach(all, GalleryFilter(), places, wheel.values.toList()) { copy(colours = setOf(it)) }
        val byName = wheel.mapValues { (_, hex) -> counts.getValue(hex) }

        assertEquals(listOf("ocean", "snow"), filterMedia(all, GalleryFilter(colours = setOf(wheel.getValue("Blue"))), places).map { it.id })
        assertEquals(2, byName["Blue"])
        assertEquals(listOf("sunflowers"), filterMedia(all, GalleryFilter(colours = setOf(wheel.getValue("Yellow"))), places).map { it.id })
        assertEquals(listOf("snow"), filterMedia(all, GalleryFilter(colours = setOf(wheel.getValue("White"))), places).map { it.id })
        assertEquals(0, byName["Purple"]) // nothing purple in the trip: shown greyed out
        assertEquals(0, byName["Black"])
        assertEquals(12, byName.size)
    }

    @Test
    fun filtersCombine() {
        fun filtered(filter: GalleryFilter) = filterMedia(items, filter, places).map { it.id }
        assertEquals(items.map { it.id }, filtered(GalleryFilter()))
        assertEquals(listOf("video"), filtered(GalleryFilter(kinds = setOf(MediaKind.Video))))
        assertEquals(listOf("sam", "nowhere"), filtered(GalleryFilter(days = setOf(LocalDate(2026, 10, 4)))))
        assertEquals(listOf("burst1", "burst2", "video"), filtered(GalleryFilter(places = setOf("Big Sur"))))
        assertEquals(listOf("burst1", "burst2"), filtered(GalleryFilter(places = setOf("Big Sur"), kinds = setOf(MediaKind.Photo))))
        assertEquals(emptyList(), filtered(GalleryFilter(places = setOf("Big Sur"), people = setOf("Sam"))))
        assertEquals(items.map { it.id }, filtered(GalleryFilter(people = setOf("Maya", "Sam"))))
    }
}
