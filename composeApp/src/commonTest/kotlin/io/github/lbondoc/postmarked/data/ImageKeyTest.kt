package io.github.lbondoc.postmarked.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageKeyTest {
    private val base = "https://bzixdjhzawvqbmskdtms.supabase.co/storage/v1/object/sign/media"
    private val path = Media.path("trip-1", "media-9", MediaKind.THUMB)

    @Test
    fun twoSignedUrlsForTheSamePathGiveTheSameKey() {
        val monday = "$base/$path?token=eyJhbGciOiJIUzI1NiJ9.first"
        val tuesday = "$base/$path?token=eyJhbGciOiJIUzI1NiJ9.second"
        assertEquals(storageKey(monday), storageKey(tuesday))
        assertEquals(path, storageKey(monday)) // the same key the grid and viewer already use
    }

    @Test
    fun differentFilesKeepDifferentKeys() {
        val display = Media.path("trip-1", "media-9", MediaKind.DISPLAY)
        assertEquals(display, storageKey("$base/$display?token=x"))
    }

    @Test
    fun otherLinksAreLeftAlone() {
        assertNull(storageKey("https://i.scdn.co/image/ab67616d0000b273"))
        assertNull(storageKey("$base/"))
    }
}
