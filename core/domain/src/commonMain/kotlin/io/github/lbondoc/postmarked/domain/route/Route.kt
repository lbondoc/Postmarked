package io.github.lbondoc.postmarked.domain.route

import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.haversineMeters
import kotlinx.datetime.LocalDate

/** Photos or videos within this distance of where a stop began belong to that stop. */
const val STOP_RADIUS_METERS = 500.0

/**
 * Faster than this between two photos, a location is treated as a GPS glitch (a phone reusing
 * an old fix), unless the photos either side agree with it.
 */
private const val GLITCH_KMH = 200.0

/** A leg covering more than this, faster than [JUMP_KMH] in a straight line, was not driven (a flight, a ferry). */
private const val JUMP_MIN_METERS = 100_000.0
private const val JUMP_KMH = 150.0

/** One located photo or video. [day] is the local calendar day where it was taken. */
data class RoutePoint(val id: String, val timeMs: Long, val day: LocalDate, val at: LatLng)

/** A place the group spent time, made of the photos taken there in a row. */
data class Stop(val at: LatLng, val day: LocalDate, val arrivedMs: Long, val leftMs: Long, val ids: List<String>)

/** Travel between two consecutive stops; it counts toward the day it arrives. */
data class Leg(val from: Stop, val to: Stop, val driven: Boolean) {
    val day: LocalDate get() = to.day
}

class RoutePlan(val stops: List<Stop>, val legs: List<Leg>) {
    /**
     * Unbroken stretches of driven legs, as lists of stops: each is one request to the road router.
     * A leg that was not driven ends one stretch, and the next starts at its far end.
     */
    fun drivenStretches(): List<List<Stop>> {
        val stretches = mutableListOf<MutableList<Stop>>()
        var current: MutableList<Stop>? = null
        for (leg in legs) {
            if (!leg.driven) {
                current = null
                continue
            }
            current = current ?: mutableListOf(leg.from).also { stretches += it }
            current += leg.to
        }
        return stretches
    }
}

/** Rebuilds where the trip went from everyone's located photos, in any order. */
fun planRoute(points: List<RoutePoint>): RoutePlan {
    val sorted = points.sortedBy { it.timeMs }
    val kept = sorted.filterIndexed { i, p -> !isGlitch(sorted.getOrNull(i - 1), p, sorted.getOrNull(i + 1)) }

    // Consecutive photos near where the current stop began join it; anything further starts a new stop.
    val groups = mutableListOf<MutableList<RoutePoint>>()
    for (p in kept) {
        val current = groups.lastOrNull()
        if (current != null && haversineMeters(current.first().at, p.at) <= STOP_RADIUS_METERS) current += p
        else groups += mutableListOf(p)
    }
    val stops = groups.map { group ->
        Stop(
            at = LatLng(group.map { it.at.lat }.average(), group.map { it.at.lng }.average()),
            day = group.first().day,
            arrivedMs = group.first().timeMs,
            leftMs = group.last().timeMs,
            ids = group.map { it.id },
        )
    }
    val legs = stops.zipWithNext { a, b ->
        val meters = haversineMeters(a.at, b.at)
        Leg(a, b, driven = !(meters > JUMP_MIN_METERS && kmh(meters, b.arrivedMs - a.leftMs) > JUMP_KMH))
    }
    return RoutePlan(stops, legs)
}

private fun kmh(meters: Double, ms: Long) = (meters / 1000) / (maxOf(ms, 60_000L) / 3_600_000.0)

private fun speedKmh(a: RoutePoint, b: RoutePoint) = kmh(haversineMeters(a.at, b.at), b.timeMs - a.timeMs)

/** A point both neighbours would have had to race to and from, while they agree with each other. */
private fun isGlitch(before: RoutePoint?, p: RoutePoint, after: RoutePoint?): Boolean {
    if (before == null || after == null) return false
    return speedKmh(before, p) > GLITCH_KMH && speedKmh(p, after) > GLITCH_KMH && speedKmh(before, after) <= GLITCH_KMH
}

/**
 * Splits a stretch into overlapping pieces of at most [max] stops, since routers cap how many
 * places one request may visit. Each piece starts where the previous one ended.
 */
fun <T> pieces(stretch: List<T>, max: Int): List<List<T>> {
    require(max >= 2)
    if (stretch.size < 2) return emptyList()
    return stretch.windowed(size = max, step = max - 1, partialWindows = true).filter { it.size >= 2 }
}

/** A leg as drawn and counted: along the roads when [snapped], else a straight line. */
data class RoutedLeg(val leg: Leg, val path: List<LatLng>, val seconds: Double?, val snapped: Boolean) {
    /** Measured along the drawn path, so the number always matches the line on the map. */
    val meters: Double get() = io.github.lbondoc.postmarked.domain.geo.pathLengthMeters(path)
}

data class DaySummary(val day: LocalDate, val meters: Double, val seconds: Double, val approximate: Boolean)

/** Distance and drive time per day of the trip, counting only legs that were driven. */
fun summarizeDays(stops: List<Stop>, routed: List<RoutedLeg>): List<DaySummary> {
    val driven = routed.filter { it.leg.driven }.groupBy { it.leg.day }
    return stops.map { it.day }.distinct().sorted().map { day ->
        val legs = driven[day].orEmpty()
        DaySummary(day, legs.sumOf { it.meters }, legs.sumOf { it.seconds ?: 0.0 }, approximate = legs.any { !it.snapped })
    }
}

/**
 * Decodes a Google-style encoded polyline, the format road routers send their paths in.
 * Valhalla (Stadia Maps) uses 6 decimal places; Google and OSRM use 5.
 */
fun decodePolyline(encoded: String, precision: Int = 6): List<LatLng> {
    var factor = 1.0
    repeat(precision) { factor *= 10 }
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0L
    var lng = 0L
    // Each coordinate is stored as the change from the previous one, in 5-bit chunks.
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(index < encoded.length) { "Polyline ends in the middle of a number" }
            val chunk = encoded[index++].code - 63
            result = result or ((chunk and 0x1f).toLong() shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (index < encoded.length) {
        lat += next()
        lng += next()
        points += LatLng(lat / factor, lng / factor)
    }
    return points
}
