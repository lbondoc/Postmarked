package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.route.DaySummary
import io.github.lbondoc.postmarked.domain.route.Leg
import io.github.lbondoc.postmarked.domain.route.RoutePlan
import io.github.lbondoc.postmarked.domain.route.RoutePoint
import io.github.lbondoc.postmarked.domain.route.RoutedLeg
import io.github.lbondoc.postmarked.domain.route.Stop
import io.github.lbondoc.postmarked.domain.route.decodePolyline
import io.github.lbondoc.postmarked.domain.route.pieces
import io.github.lbondoc.postmarked.domain.route.planRoute
import io.github.lbondoc.postmarked.domain.route.summarizeDays
import io.ktor.client.statement.bodyAsText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The most stops one routing request may hold; matches the server function. */
private const val MAX_POINTS_PER_REQUEST = 20

@Serializable
data class LocatedMedia(
    val id: String,
    val kind: MediaKind,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("utc_offset_minutes") val utcOffsetMinutes: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

class TripRoute(
    val plan: RoutePlan,
    val legs: List<RoutedLeg>,
    val days: List<DaySummary>,
    val media: Map<String, LocatedMedia>,
    /** Why some legs are straight lines instead of following roads; null when all are on roads. */
    val routingProblem: String?,
)

@Serializable
private class RouterAnswer(val legs: List<RouterLeg>)

@Serializable
private class RouterLeg(val shape: String, val seconds: Double)

private val lenientJson = Json { ignoreUnknownKeys = true }

object Routes {
    // shortcut: kept in memory for this app session only, so each launch asks the router again;
    // store routes on the server when manual route editing arrives (Phase 2).
    private val cache = mutableMapOf<List<LatLng>, List<RouterLeg>>()

    /** Rebuilds the trip's route from everyone's located photos and videos. */
    suspend fun build(tripId: String): TripRoute {
        val media = supabase.from("media")
            .select(Columns.list("id", "kind", "captured_at", "utc_offset_minutes", "lat", "lng")) {
                filter { eq("trip_id", tripId) }
            }.decodeList<LocatedMedia>()
        val plan = planRoute(media.mapNotNull { it.toRoutePoint() })

        var problem: String? = null
        val routed = mutableListOf<RoutedLeg>()
        for (leg in plan.legs.filterNot { it.driven }) routed += straight(leg)
        for (piece in plan.drivenStretches().flatMap { pieces(it, MAX_POINTS_PER_REQUEST) }) {
            val legs = piece.zipWithNext { a, b -> Leg(a, b, driven = true) }
            val answer = try {
                cache.getOrPut(piece.map { it.at }) { askRouter(piece) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = problem ?: e.routingMessage()
                null
            }
            routed += legs.mapIndexed { i, leg ->
                answer?.get(i)?.let { RoutedLeg(leg, decodePolyline(it.shape), it.seconds, snapped = true) } ?: straight(leg)
            }
        }
        return TripRoute(plan, routed, summarizeDays(plan.stops, routed), media.associateBy { it.id }, problem)
    }

    private fun straight(leg: Leg) = RoutedLeg(leg, listOf(leg.from.at, leg.to.at), seconds = null, snapped = false)

    private suspend fun askRouter(stops: List<Stop>): List<RouterLeg> {
        val response = supabase.functions.invoke("route", buildJsonObject {
            putJsonArray("points") {
                for (stop in stops) addJsonObject { put("lat", stop.at.lat); put("lng", stop.at.lng) }
            }
        })
        return lenientJson.decodeFromString<RouterAnswer>(response.bodyAsText()).legs
    }
}

/** The function answers errors as {"error": "..."}; a missing function is a plain 404. */
private fun Exception.routingMessage(): String = when (this) {
    is NotFoundRestException -> "road routing isn't set up yet"
    is RestException -> runCatching { lenientJson.parseToJsonElement(error).jsonObject.getValue("error").jsonPrimitive.content }
        .getOrElse { userMessage() }
    else -> userMessage()
}

private fun LocatedMedia.toRoutePoint(): RoutePoint? {
    if (lat == null || lng == null) return null
    val instant = Instant.parse(capturedAt)
    // The calendar day where it was taken: shift by the recorded time zone, else use the phone's.
    val day = if (utcOffsetMinutes != null) (instant + utcOffsetMinutes.minutes).toLocalDateTime(TimeZone.UTC).date
    else instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return RoutePoint(id, instant.toEpochMilliseconds(), day, LatLng(lat, lng))
}
