package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.exceptions.HttpRequestException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Where the offline copies live: app files on Android, `localStorage` on the web (SPEC §4). */
interface TripCacheStore {
    fun read(key: String): String?
    fun write(key: String, value: String)

    /** Signing out forgets every saved trip: they belong to the account that loaded them. */
    fun clear()
}

/** Keeps nothing: iOS for now, and tests that don't care. */
object NoTripCache : TripCacheStore {
    override fun read(key: String): String? = null
    override fun write(key: String, value: String) = Unit
    override fun clear() = Unit
}

/** What a trip's page needs to show it offline: the trip with its members, its media rows and thumbnail links. */
@Serializable
data class TripSnapshot(
    val trip: Trip,
    val media: List<TripMedia> = emptyList(),
    /** Expired after an hour, but the image cache is keyed by Storage path (F4), so cached thumbnails still show. */
    val thumbnails: Map<String, String> = emptyMap(),
)

/** A load's result, and whether it came from the saved copy because the network failed. */
class Loaded<T>(val value: T, val offline: Boolean)

/**
 * The trip list and each trip's snapshot, saved as JSON after every successful load and read back when a load
 * fails for lack of network. A missing or unreadable copy reads as nothing, never as a crash: it is only a cache.
 */
class TripCache(private val store: TripCacheStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun saveTrips(trips: List<Trip>) = write(TRIPS, ListSerializer(Trip.serializer()), trips)
    fun trips(): List<Trip> = read(TRIPS, ListSerializer(Trip.serializer())).orEmpty()

    /** The trip list's cover thumbnails, by trip id (see [TripSnapshot.thumbnails] on why old links still work). */
    fun saveCovers(covers: Map<String, String>) = write(COVERS, MapSerializer(String.serializer(), String.serializer()), covers)
    fun covers(): Map<String, String> = read(COVERS, MapSerializer(String.serializer(), String.serializer())).orEmpty()

    fun saveTrip(snapshot: TripSnapshot) = write(tripKey(snapshot.trip.id), TripSnapshot.serializer(), snapshot)
    fun trip(id: String): TripSnapshot? = read(tripKey(id), TripSnapshot.serializer())

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) =
        runCatching { store.write(key, json.encodeToString(serializer, value)) }.let { } // a full disk only loses the cache

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        runCatching { store.read(key)?.let { json.decodeFromString(serializer, it) } }.getOrNull()

    private fun tripKey(id: String) = "trip-$id"

    companion object {
        private const val TRIPS = "trips"
        private const val COVERS = "covers"

        /** Set by the platform at start-up (Android: app files; web: localStorage). */
        var store: TripCacheStore = NoTripCache

        val shared: TripCache get() = TripCache(store)
    }
}

/** True when [e] means the server couldn't be reached, as opposed to the server refusing. */
fun isOffline(e: Throwable): Boolean = e is HttpRequestException

/**
 * Loads fresh data and saves it; when the network fails and a saved copy exists, returns that copy marked
 * offline instead. Any other failure (or no saved copy) is thrown as before.
 */
suspend fun <T : Any> loadOrCached(
    load: suspend () -> T,
    save: (T) -> Unit,
    cached: () -> T?,
    offline: (Throwable) -> Boolean = ::isOffline,
): Loaded<T> = try {
    load().also(save).let { Loaded(it, offline = false) }
} catch (e: Exception) {
    val copy = if (offline(e)) cached() else null
    copy?.let { Loaded(it, offline = true) } ?: throw e
}
