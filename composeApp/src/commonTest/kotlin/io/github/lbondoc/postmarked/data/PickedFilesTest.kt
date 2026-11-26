package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.media.ExifInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class PickedFilesTest {
    private val la = TimeZone.of("America/Los_Angeles")

    @Test
    fun heicIsRefusedByTypeOrByName() {
        assertEquals(HEIC_REFUSED, pickedFileProblem("IMG_0001.HEIC", "image/heic", 2_000_000))
        assertEquals(HEIC_REFUSED, pickedFileProblem("IMG_0001.heic", "", 2_000_000)) // some browsers give no type
        assertEquals(MediaKind.Photo, pickedKind("IMG_0001.heic", ""))
    }

    @Test
    fun videosOver50MbAreRefusedWithThePhonesMessage() {
        assertNull(pickedFileProblem("clip.mp4", "video/mp4", MAX_UPLOAD_BYTES))
        assertEquals(TOO_BIG, pickedFileProblem("clip.mp4", "video/mp4", MAX_UPLOAD_BYTES + 1))
        assertNull(pickedFileProblem("photo.jpg", "image/jpeg", 8_000_000))
    }

    @Test
    fun onlyPhotosAndVideosArePicked() {
        assertEquals(MediaKind.Video, pickedKind("clip.mov", "video/quicktime"))
        assertNull(pickedKind("notes.pdf", "application/pdf"))
    }

    @Test
    fun exifTimeWithOffsetIsExact() {
        val m = pickedMedia("u", MediaKind.Photo, ExifInfo("2026:10:03 23:30:00", "-07:00", LatLng(37.77, -122.42)), 0, TimeZone.UTC)
        assertEquals(Instant.parse("2026-10-04T06:30:00Z").toEpochMilliseconds(), m.capturedAtMs)
        assertEquals(LocalDate(2026, 10, 3), m.takenOn) // the day where it was taken, not in UTC
        assertEquals(-420, m.utcOffsetMinutes)
        assertEquals(37.77, m.lat)
    }

    @Test
    fun exifTimeWithoutOffsetIsReadInTheLocalZone() {
        val m = pickedMedia("u", MediaKind.Photo, ExifInfo("2026:10:03 23:30:00"), 0, la)
        assertEquals(Instant.parse("2026-10-04T06:30:00Z").toEpochMilliseconds(), m.capturedAtMs) // PDT is -7
        assertEquals(LocalDate(2026, 10, 3), m.takenOn)
        assertNull(m.utcOffsetMinutes)
        assertNull(m.location)
    }

    @Test
    fun noExifFallsBackToTheFileDate() {
        val modified = Instant.parse("2026-10-04T02:00:00Z").toEpochMilliseconds()
        val m = pickedMedia("u", MediaKind.Video, ExifInfo(), modified, la)
        assertEquals(modified, m.capturedAtMs)
        assertEquals(LocalDate(2026, 10, 3), m.takenOn) // 7 pm the evening before in Los Angeles
    }

    @Test
    fun noExifDateMeansTheDateIsNotKnownAndOnlySuggested() {
        val start = LocalDate(2026, 10, 3)
        val end = LocalDate(2026, 10, 6)
        val modified = Instant.parse("2026-10-05T02:00:00Z").toEpochMilliseconds() // Oct 4, 7 pm in Los Angeles
        val screenshot = pickedMedia("s", MediaKind.Photo, ExifInfo(), modified, la)
        assertEquals(false, screenshot.dateKnown)
        assertEquals(LocalDate(2026, 10, 4), suggestedDay(screenshot, start, end), "modified during the trip: that day")
        val copiedLater = pickedMedia("c", MediaKind.Photo, ExifInfo(), Instant.parse("2026-11-20T12:00:00Z").toEpochMilliseconds(), la)
        assertNull(suggestedDay(copiedLater, start, end), "modified after the trip: no day suggested")
        val camera = pickedMedia("p", MediaKind.Photo, ExifInfo("2026:10:04 09:00:00"), modified, la)
        assertEquals(true, camera.dateKnown)
        assertNull(suggestedDay(camera, start, end), "a file with its own date needs no suggestion")
    }
}
