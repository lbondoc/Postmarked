package io.github.lbondoc.postmarked.domain.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 coordinate in decimal degrees. Build untrusted input with [gpsOrNull]. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Turns raw metadata into a coordinate, or null when it cannot be a real fix.
 * Cameras write (0, 0) when they had no GPS lock, so that counts as "no location".
 */
fun gpsOrNull(lat: Double, lng: Double): LatLng? = when {
    lat.isNaN() || lng.isNaN() -> null
    lat !in -90.0..90.0 || lng !in -180.0..180.0 -> null
    lat == 0.0 && lng == 0.0 -> null
    else -> LatLng(lat, lng)
}

/** Mean Earth radius (IUGG), the usual choice for haversine. */
const val EARTH_RADIUS_M = 6_371_008.8

// An extension function: adds `.toRadians()` to Double without subclassing it.
private fun Double.toRadians() = this * PI / 180.0

/**
 * Great-circle distance in metres. Treats the Earth as a sphere, so it can be off by
 * up to about 0.5% against a survey-grade answer; fine for trip mileage.
 */
fun haversineMeters(a: LatLng, b: LatLng): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLng = (b.lng - a.lng).toRadians()
    val h = sin(dLat / 2).pow(2) +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLng / 2).pow(2)
    // Rounding can push h a hair past 1 for antipodal points, which would make asin return NaN.
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceAtMost(1.0)))
}

/** Length of a line through [points] in order, in metres. */
fun pathLengthMeters(points: List<LatLng>): Double =
    points.zipWithNext(::haversineMeters).sum()

private val ISO_6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

/**
 * Parses the location string Android reports for videos, e.g. "+37.4219-122.0840/"
 * (an altitude may follow the longitude; it is ignored).
 * shortcut: decimal degrees only, add the ±DDMM.MMM form if a camera ever writes it.
 */
fun parseIso6709(text: String): LatLng? {
    // `destructured` unpacks the regex groups; `?: return null` exits early on no match.
    val (lat, lng) = ISO_6709.find(text.trim())?.destructured ?: return null
    return gpsOrNull(lat.toDouble(), lng.toDouble())
}
