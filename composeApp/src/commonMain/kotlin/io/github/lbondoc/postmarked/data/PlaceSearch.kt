package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.geo.Place
import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.nameFromReverse
import io.github.lbondoc.postmarked.domain.geo.placesFromNominatim
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

private val http by lazy { HttpClient() }

/**
 * Finds places by name with their outlines, using OpenStreetMap's free Nominatim service.
 * Its rules: at most one request a second, no search-as-you-type, and an identifying User-Agent.
 * shortcut: called straight from the phone, move behind our own caching server before a public launch.
 */
suspend fun searchPlaces(query: String): List<Place> {
    val body = http.get("https://nominatim.openstreetmap.org/search") {
        parameter("q", query)
        parameter("format", "jsonv2")
        parameter("polygon_geojson", 1)
        parameter("polygon_threshold", 0.005) // simplify outlines to roughly 500 m; Alaska drops to ~2,000 points
        parameter("limit", 6)
        header(HttpHeaders.UserAgent, "Postmarked-Android/0.1")
    }.bodyAsText()
    return placesFromNominatim(body)
}

private val namesLock = Mutex()
private val names = mutableMapOf<String, String?>()
private var lastLookup: TimeMark? = null

/**
 * The name of the place at [at] (a town, or the park or forest out in the wilds), or null if
 * unknown. Looked up one a second, per Nominatim's rules, and remembered for as long as the app
 * runs, so reopening a trip asks again only for new places.
 * shortcut: remembered in memory only; store names with the trip if lookups become slow or frequent.
 */
suspend fun placeName(at: LatLng): String? = namesLock.withLock {
    // About 100 m: photos taken at the same stop share one lookup.
    val key = "${(at.lat * 1000).roundToInt()},${(at.lng * 1000).roundToInt()}"
    if (key in names) return names[key]
    lastLookup?.let { delay(1.seconds - it.elapsedNow()) } // a negative delay doesn't wait
    val name = try {
        nameFromReverse(
            http.get("https://nominatim.openstreetmap.org/reverse") {
                parameter("lat", at.lat)
                parameter("lon", at.lng)
                parameter("format", "jsonv2")
                parameter("zoom", 14) // town and village level, not street addresses
                header(HttpHeaders.UserAgent, "Postmarked-Android/0.1")
            }.bodyAsText(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null // offline or refused: stay numbered, and try again next time
    } finally {
        lastLookup = TimeSource.Monotonic.markNow()
    }
    names[key] = name
    name
}
