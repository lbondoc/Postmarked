package io.github.lbondoc.postmarked.domain.trip

import io.github.lbondoc.postmarked.domain.geo.EARTH_RADIUS_M
import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** How far outside a region a photo can be and still count as "along the way". */
const val ALONG_THE_WAY_METERS = 25_000.0

enum class TripMatch { Both, DatesOnly, LocationOnly, Neither }

/**
 * Whether something taken on [takenOn] at [location] belongs to a trip. Both dates are
 * inclusive. Without a location only the dates can match.
 */
fun matchTrip(takenOn: LocalDate, location: LatLng?, start: LocalDate, end: LocalDate, area: TripArea): TripMatch {
    val inDates = takenOn in start..end
    val inPlace = location != null && area.covers(location)
    return when {
        inDates && inPlace -> TripMatch.Both
        inDates -> TripMatch.DatesOnly
        inPlace -> TripMatch.LocationOnly
        else -> TripMatch.Neither
    }
}

/** A trip's location: the GeoJSON Polygons and MultiPolygons of its regions. */
class TripArea(regions: List<JsonObject>) {
    // A polygon is a list of rings (closed lists of points); rings after the first are holes.
    private val polygons: List<List<List<LatLng>>> = regions.flatMap { shape ->
        val coordinates = shape.getValue("coordinates").jsonArray
        when (shape.getValue("type").jsonPrimitive.content) {
            "Polygon" -> listOf(polygon(coordinates))
            "MultiPolygon" -> coordinates.map { polygon(it.jsonArray) }
            else -> throw IllegalArgumentException("A trip region must be a Polygon or MultiPolygon")
        }
    }

    /** True when [point] is inside a region, or within [toleranceMeters] of one. */
    fun covers(point: LatLng, toleranceMeters: Double = ALONG_THE_WAY_METERS): Boolean =
        polygons.any { inside(point, it) || distanceToEdges(point, it) <= toleranceMeters }

    private fun polygon(rings: JsonArray) = rings.map { ring ->
        // GeoJSON positions are [longitude, latitude].
        ring.jsonArray.map { val p = it.jsonArray; LatLng(p[1].jsonPrimitive.double, p[0].jsonPrimitive.double) }
    }
}

/**
 * Ray casting: count how many edges a line heading east from [p] crosses; odd means inside.
 * Running it over every ring at once makes points in a hole come out as outside.
 */
private fun inside(p: LatLng, rings: List<List<LatLng>>): Boolean {
    var inside = false
    for (ring in rings) for ((a, b) in ring.zipWithNext()) {
        if ((a.lat > p.lat) != (b.lat > p.lat) &&
            p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
        ) inside = !inside
    }
    return inside
}

private const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180

/**
 * Shortest distance from [p] to any edge, in metres. Flattens the map around [p] (fine at the
 * tens-of-kilometres scale this is used for), then measures point-to-segment distance.
 */
private fun distanceToEdges(p: LatLng, rings: List<List<LatLng>>): Double {
    val xScale = cos(p.lat * PI / 180) * METERS_PER_DEGREE
    fun x(q: LatLng): Double {
        var dLng = q.lng - p.lng
        if (abs(dLng) > 180) dLng -= 360 * kotlin.math.sign(dLng) // the short way across the date line
        return dLng * xScale
    }
    fun y(q: LatLng) = (q.lat - p.lat) * METERS_PER_DEGREE

    var best = Double.POSITIVE_INFINITY
    for (ring in rings) for ((a, b) in ring.zipWithNext()) {
        val ax = x(a); val ay = y(a)
        val dx = x(b) - ax; val dy = y(b) - ay
        val lengthSquared = dx * dx + dy * dy
        // How far along the edge the closest point is: 0 = at a, 1 = at b.
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        best = minOf(best, hypot(ax + t * dx, ay + t * dy))
    }
    return best
}
