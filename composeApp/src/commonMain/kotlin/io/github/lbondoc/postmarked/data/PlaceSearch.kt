package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.geo.Place
import io.github.lbondoc.postmarked.domain.geo.placesFromNominatim
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
