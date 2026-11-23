package io.github.lbondoc.postmarked.domain.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class CaptionTest {
    @Test
    fun dateSpansReadNaturally() {
        assertEquals("Oct 3 – 5, 2026", dateSpan(LocalDate(2026, 10, 3), LocalDate(2026, 10, 5)))
        assertEquals("Sep 30 – Oct 2, 2026", dateSpan(LocalDate(2026, 9, 30), LocalDate(2026, 10, 2)))
        assertEquals("Dec 30, 2026 – Jan 2, 2027", dateSpan(LocalDate(2026, 12, 30), LocalDate(2027, 1, 2)))
        assertEquals("Oct 3, 2026", dateSpan(LocalDate(2026, 10, 3), LocalDate(2026, 10, 3)))
    }

    @Test
    fun hashtagsAreLettersAndDigits() {
        assertEquals("#bigsur", hashtag("Big Sur"))
        assertEquals("#joshuatreenationalpark", hashtag("Joshua Tree National Park"))
        assertEquals("#route66", hashtag("Route 66!"))
        assertEquals("#sãopaulo", hashtag("São Paulo"), "accented letters are kept; Instagram allows them")
        assertNull(hashtag("—"))
    }

    @Test
    fun aFullCaption() {
        val text = caption(
            tripName = "Big Sur Road Trip",
            start = LocalDate(2026, 10, 3), end = LocalDate(2026, 10, 5),
            miles = 412, stops = 12, places = listOf("Big Sur", "Monterey"),
            song = SongCredit("Never Gonna Give You Up", "Rick Astley"), colour = "Yellow",
        )
        assertEquals(
            """
            Big Sur Road Trip
            Oct 3 – 5, 2026 · 412 miles · 12 stops
            📍 Big Sur · Monterey
            🎵 Never Gonna Give You Up — Rick Astley

            #roadtrip #bigsur #monterey #yellowaesthetic #postmarked
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun missingPartsAreLeftOutCleanly() {
        val text = caption("Weekend", LocalDate(2026, 10, 3), LocalDate(2026, 10, 3), miles = null, stops = 1,
            places = emptyList(), song = SongCredit("Untitled", artist = " "), colour = null)
        assertEquals("Weekend\nOct 3, 2026\n🎵 Untitled\n\n#roadtrip #postmarked", text)
    }

    @Test
    fun hashtagsAreCappedAndUnique() {
        val text = caption("T", LocalDate(2026, 1, 1), LocalDate(2026, 1, 2), 10, 2,
            places = List(20) { "Place $it" } + "Big Sur" + "big sur", song = null, colour = null, maxTags = 5)
        val tags = text.substringAfterLast("\n").split(" ")
        assertEquals(5, tags.size)
        assertTrue(tags.toSet().size == tags.size)
    }
}
