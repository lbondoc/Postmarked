package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.Profile
import io.github.lbondoc.postmarked.data.TripMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

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
}
