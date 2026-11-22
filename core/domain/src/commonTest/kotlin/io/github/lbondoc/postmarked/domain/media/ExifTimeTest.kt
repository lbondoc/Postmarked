package io.github.lbondoc.postmarked.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class ExifTimeTest {
    @Test
    fun readsTheDayFromDateTimeOriginal() {
        assertEquals(LocalDate(2026, 10, 3), exifDate("2026:10:03 14:22:01"))
        assertEquals(LocalDate(2026, 10, 3), exifDate("2026:10:03 23:59:59"), "late evening stays on its own day")
    }

    @Test
    fun missingOrZeroedDatesAreNull() {
        assertNull(exifDate(null))
        assertNull(exifDate(""))
        assertNull(exifDate("    :  :     :  :  "))
        assertNull(exifDate("0000:00:00 00:00:00"))
        assertNull(exifDate("2026:13:01 00:00:00"))
        assertNull(exifDate("2026-10-03 14:22:01"), "EXIF uses colons; a dash means something else wrote it")
    }

    @Test
    fun readsOffsets() {
        assertEquals(-420, exifOffsetMinutes("-07:00"))
        assertEquals(330, exifOffsetMinutes("+05:30"))
        assertEquals(0, exifOffsetMinutes("+00:00"))
        assertEquals(840, exifOffsetMinutes("+14:00"))
    }

    @Test
    fun badOffsetsAreNull() {
        assertNull(exifOffsetMinutes(null))
        assertNull(exifOffsetMinutes("   :  "))
        assertNull(exifOffsetMinutes("07:00"))
        assertNull(exifOffsetMinutes("+15:00"))
        assertNull(exifOffsetMinutes("+05:75"))
    }
}
