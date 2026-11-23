package io.github.lbondoc.postmarked.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewSinceTest {
    private class MemoryStore : TripCacheStore {
        val files = mutableMapOf<String, String>()
        override fun read(key: String) = files[key]
        override fun write(key: String, value: String) { files[key] = value }
        override fun clear() = files.clear()
    }

    private fun ms(t: String) = Instant.parse(t).toEpochMilliseconds()
    private fun added(id: String, by: String, at: String) =
        TripMedia(id, MediaKind.Photo, by, capturedAt = "2026-10-03T12:00:00Z", createdAt = at)

    private val media = listOf(
        added("old", "sam", "2026-10-03T18:00:00Z"),
        added("mine", "me", "2026-10-04T09:00:00Z"),
        added("new1", "sam", "2026-10-04T09:30:00.123456+00:00"), // Postgres's own timestamp format
        added("new2", "maya", "2026-10-04T10:00:00Z"),
    )

    @Test
    fun yourOwnUploadsDontCount() {
        assertEquals(2, newFromFriends(media, ms("2026-10-04T08:00:00Z"), me = "me"))
        assertEquals(3, newFromFriends(media, ms("2026-10-04T08:00:00Z"), me = "someone else"))
    }

    @Test
    fun theCountResetsOnOpeningTheTrip() {
        val lastOpened = LastOpened(TripCache(MemoryStore()))
        lastOpened.opened("t1", ms("2026-10-04T08:00:00Z"))
        assertEquals(2, newFromFriends(media, lastOpened.of("t1"), me = "me"))
        lastOpened.opened("t1", ms("2026-10-04T11:00:00Z")) // opening the trip now
        assertEquals(0, newFromFriends(media, lastOpened.of("t1"), me = "me"))
    }

    @Test
    fun aTripNeverOpenedHereCountsNothing() {
        assertEquals(0, newFromFriends(media, LastOpened(TripCache(MemoryStore())).of("t1"), me = "me"))
    }
}
