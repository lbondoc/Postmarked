package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class TripCacheTest {
    private class MemoryStore : TripCacheStore {
        val files = mutableMapOf<String, String>()
        override fun read(key: String) = files[key]
        override fun write(key: String, value: String) { files[key] = value }
        override fun clear() = files.clear()
    }

    private val trip = Trip(
        id = "t1", name = "Big Sur", startDate = LocalDate(2026, 10, 3), endDate = LocalDate(2026, 10, 5),
        regions = listOf(Region("Big Sur, California")), members = listOf(Member("u1", TripRole.Owner, Profile("Maya"))),
        coverMediaId = "m1",
    )
    private val photo = TripMedia("m1", MediaKind.Photo, "u1", "2026-10-03T18:00:00Z", lat = 36.27, lng = -121.81)

    private fun <T> now(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    @Test
    fun savedThenLoadedComesBackTheSame() {
        val cache = TripCache(MemoryStore())
        cache.saveTrips(listOf(trip))
        cache.saveTrip(TripSnapshot(trip, listOf(photo), mapOf("m1" to "https://x/sign/media/t1/m1/thumb.jpg?token=a")))
        assertEquals(listOf(trip), cache.trips())
        assertEquals(TripSnapshot(trip, listOf(photo), mapOf("m1" to "https://x/sign/media/t1/m1/thumb.jpg?token=a")), cache.trip("t1"))
        assertNull(cache.trip("never-opened"))
    }

    @Test
    fun aCorruptFileLoadsAsEmptyWithoutCrashing() {
        val store = MemoryStore()
        store.files["trips"] = "{not json"
        store.files["trip-t1"] = """{"trip": 42}"""
        val cache = TripCache(store)
        assertEquals(emptyList(), cache.trips())
        assertNull(cache.trip("t1"))
    }

    @Test
    fun aFailedNetworkLoadReturnsTheCachedCopy() {
        val cache = TripCache(MemoryStore())
        val fresh = now { loadOrCached({ listOf(trip) }, cache::saveTrips, cache::trips) }
        assertFalse(fresh.offline)

        val offline = now {
            loadOrCached<List<Trip>>({ throw HttpRequestException("Unable to resolve host", HttpRequestBuilder()) }, cache::saveTrips, cache::trips)
        }
        assertTrue(offline.offline)
        assertEquals(listOf(trip), offline.value)
    }

    @Test
    fun otherFailuresAndAMissingCopyStillFail() {
        val cache = TripCache(MemoryStore())
        // Offline with nothing saved: the error shows as before.
        assertFailsWith<HttpRequestException> {
            now { loadOrCached<TripSnapshot>({ throw HttpRequestException("offline", HttpRequestBuilder()) }, cache::saveTrip, { cache.trip("t1") }) }
        }
        // The server answered with a refusal: not an offline case, even with a saved copy.
        cache.saveTrips(listOf(trip))
        assertFailsWith<IllegalStateException> { now { loadOrCached<List<Trip>>({ error("permission denied") }, cache::saveTrips, cache::trips) } }
    }
}
