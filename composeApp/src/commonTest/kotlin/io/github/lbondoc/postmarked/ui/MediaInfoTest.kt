package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.Profile
import io.github.lbondoc.postmarked.data.TripMedia
import kotlin.test.Test
import io.github.lbondoc.postmarked.data.toRoutePoint
import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class MediaInfoTest {
    // 01:30 UTC on Oct 4 is 6:30 PM on Oct 3 where it was taken (UTC-7): the local time and day must follow that.
    private val photo = TripMedia(
        id = "p", kind = MediaKind.Photo, contributor = "u1", capturedAt = "2026-10-04T01:30:00Z",
        utcOffsetMinutes = -420, profile = Profile("Maya"),
    )
    private val start = LocalDate(2026, 10, 2)

    @Test
    fun givesThePersonLocalTimeDayStopAndPlace() {
        assertEquals(
            listOf("Taken by Maya", "Oct 3, 2026, 6:30 PM", "Day 2", "Stop 3 · Big Sur", "Near Big Sur, California", "4032 × 3024 px · 3.2 MB"),
            infoLines(photo, start, "Stop 3 · Big Sur", "Big Sur, California", pixels = 4032 to 3024, bytes = 3_210_000),
        )
    }

    @Test
    fun leavesOutWhatIsntKnown() {
        val anonymous = photo.copy(profile = null)
        assertEquals(listOf("Oct 3, 2026, 6:30 PM", "Day 2"), infoLines(anonymous, start, stop = null, place = null))
        // Taken before the trip started: no day number rather than "Day 0".
        assertEquals(listOf("Taken by Maya", "Oct 3, 2026, 6:30 PM"), infoLines(photo, LocalDate(2026, 10, 5), null, null))
    }

    @Test
    fun videosShowTheirLengthInsteadOfPixels() {
        val video = photo.copy(kind = MediaKind.Video)
        assertEquals("1:05 · 48 KB", infoLines(video, start, null, null, pixels = 1920 to 1080, bytes = 48_000, seconds = 64.6).last())
    }

    @Test
    fun byHandLinesAppearOnlyWhenTheFlagsAreSet() {
        val camera = infoLines(photo, start, null, "Big Sur, California")
        assertFalse(camera.any { "by hand" in it }, "camera values say nothing")
        assertEquals(
            listOf("Taken by Maya", "Oct 3, 2026, 6:30 PM", "Date set by hand", "Day 2", "Near Big Sur, California", "Location set by hand"),
            infoLines(photo.copy(dateByHand = true, locationByHand = true), start, null, "Big Sur, California"),
        )
        assertEquals(
            listOf("Taken by Maya", "Oct 3, 2026, 6:30 PM", "Day 2", "Location set by hand"),
            infoLines(photo.copy(locationByHand = true), start, null, place = null),
            "still said while the place name is loading",
        )
    }

    @Test
    fun aHandSetPlaceIsARoutePointLikeGps() {
        val placed = photo.copy(lat = 36.27, lng = -121.81, locationByHand = true)
        assertEquals(LatLng(36.27, -121.81), placed.toRoutePoint()?.at)
    }

    @Test
    fun aChosenMomentKeepsTheCamerasOffset() {
        assertEquals(Instant.parse("2026-10-03T21:15:00Z"), momentOf(LocalDate(2026, 10, 3), LocalTime(14, 15), -420, TimeZone.UTC))
        assertEquals(Instant.parse("2026-10-03T19:00:00Z"), momentOf(LocalDate(2026, 10, 3), null, -420, TimeZone.UTC), "noon without a time")
        assertEquals(LocalTime(9, 5), clockOf(" 9:05 "))
        assertEquals(null, clockOf("25:00"))
        assertEquals(null, clockOf("noon"))
    }
}
