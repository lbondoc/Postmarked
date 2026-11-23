package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.lbondoc.postmarked.domain.geo.Place
import io.github.lbondoc.postmarked.domain.trip.TripArea
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// @Serializable makes the compiler generate the JSON reading/writing code for a class;
// @SerialName maps a Kotlin property to the database's snake_case column name.

@Serializable
enum class TripRole {
    @SerialName("owner") Owner,
    @SerialName("editor") Editor,
    @SerialName("viewer") Viewer;

    val canEdit get() = this != Viewer
}

@Serializable
data class Profile(@SerialName("display_name") val displayName: String)

@Serializable
data class Member(
    @SerialName("user_id") val userId: String,
    val role: TripRole,
    val profile: Profile,
)

@Serializable
data class Region(val label: String)

@Serializable
data class Trip(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate,
    @SerialName("trip_regions") val regions: List<Region> = emptyList(),
    @SerialName("trip_members") val members: List<Member> = emptyList(),
    @SerialName("theme_id") val themeId: String? = null,
    /** {"accent": "#RRGGBB"} when the owner picked an accent colour. */
    @SerialName("theme_overrides") val themeOverrides: JsonObject? = null,
    /** The photo shown on the trip list; always one of this trip's own (the database checks). */
    @SerialName("cover_media_id") val coverMediaId: String? = null,
) {
    val myRole: TripRole? get() = members.firstOrNull { it.userId == myUserId }?.role
    val customAccent: String? get() = (themeOverrides?.get("accent") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/** Everything the app asks the backend about trips. All calls need a signed-in user. */
object Trips {
    // "trip_regions(label)" pulls each trip's regions along in the same request.
    private const val SUMMARY = "id,name,start_date,end_date,theme_id,theme_overrides,cover_media_id,trip_regions(label)"
    private const val DETAIL = "$SUMMARY,trip_members(user_id,role,profile:profiles(display_name))"

    /** Trips the user is a member of; the server's access rules do the filtering. */
    suspend fun mine(): List<Trip> =
        supabase.from("trips").select(Columns.raw(SUMMARY)) { order("start_date", Order.DESCENDING) }.decodeList()

    suspend fun byId(id: String): Trip =
        supabase.from("trips").select(Columns.raw(DETAIL)) { filter { eq("id", id) } }.decodeSingle()

    /** Creates the trip with the caller as owner and returns its id. */
    suspend fun create(name: String, start: LocalDate, end: LocalDate, regions: List<Place>): String =
        supabase.postgrest.rpc("create_trip", buildJsonObject {
            put("p_name", name)
            put("p_start_date", start.toString())
            put("p_end_date", end.toString())
            putJsonArray("p_regions") {
                for (place in regions) addJsonObject {
                    put("label", place.label.take(120)) // the column's limit
                    put("source", "place")
                    put("geometry", place.geometry)
                }
            }
        }).decodeAs()

    /** Returns a fresh invite token. The server keeps only its hash, so it cannot be shown again. */
    /** Owners and editors set the album theme; everyone on the trip then sees it. */
    /** Owner and editors only; [mediaId] must be in this trip. Null removes the cover. */
    suspend fun setCover(tripId: String, mediaId: String?) {
        supabase.from("trips").update(buildJsonObject { put("cover_media_id", mediaId) }) { filter { eq("id", tripId) } }
    }

    suspend fun setTheme(tripId: String, themeId: String, accent: String?) {
        supabase.from("trips").update(buildJsonObject {
            put("theme_id", themeId)
            put("theme_overrides", buildJsonObject { accent?.let { put("accent", it) } })
        }) { filter { eq("id", tripId) } }
    }

    suspend fun createInvite(tripId: String, role: TripRole): String =
        supabase.postgrest.rpc("create_invite", buildJsonObject {
            put("p_trip", tripId)
            put("p_role", role.name.lowercase())
        }).decodeAs()

    /** Redeems an invite token and returns the id of the trip it belongs to. */
    suspend fun join(token: String): String =
        supabase.postgrest.rpc("join_trip", buildJsonObject { put("p_token", token) }).decodeAs()

    suspend fun leave(tripId: String) {
        val me = myUserId ?: return
        supabase.from("trip_members").delete { filter { eq("trip_id", tripId); eq("user_id", me) } }
    }

    /** Files first: once the trip row is gone, nobody is allowed to see or remove them. */
    suspend fun delete(tripId: String) {
        // shortcut: files whose upload is still in flight on someone's phone are missed, add a server-side sweep before launch.
        Media.deleteAllFiles(tripId)
        supabase.from("trips").delete { filter { eq("id", tripId) } }
    }

    /**
     * The trip's dates and area, which decide which camera-roll items belong to it. Saved for offline use, so
     * photos can be picked and queued in airplane mode (they upload once there is a network).
     */
    suspend fun bounds(tripId: String): TripBounds {
        val cache = TripCache.shared
        val key = "bounds-$tripId"
        val row = loadOrCached(
            {
                supabase.from("trips").select(Columns.raw("start_date,end_date,trip_regions(geojson)")) {
                    filter { eq("id", tripId) }
                }.decodeSingle<BoundsRow>()
            },
            { cache.put(key, BoundsRow.serializer(), it) },
            { cache.get(key, BoundsRow.serializer()) },
        ).value
        return TripBounds(row.startDate, row.endDate, TripArea(row.regions.map { it.geojson }))
    }
}

/** Each of the trip's named places, for filtering the gallery by place. */
suspend fun Trips.places(tripId: String): Map<String, TripArea> =
    supabase.from("trip_regions").select(Columns.list("label", "geojson")) { filter { eq("trip_id", tripId) } }
        .decodeList<NamedShape>()
        .groupBy({ it.label }, { it.geojson })
        .mapValues { (_, shapes) -> TripArea(shapes) }

@Serializable
private class NamedShape(val label: String, val geojson: JsonObject)

class TripBounds(val start: LocalDate, val end: LocalDate, val area: TripArea)

@Serializable
private class BoundsRow(
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate,
    @SerialName("trip_regions") val regions: List<RegionShape>,
)

@Serializable
private class RegionShape(val geojson: JsonObject)

/** What an invite looks like outside the app; the QR code encodes the same text. */
fun inviteLink(token: String) = "postmarked://join/$token"

/** Accepts either a full invite link or the bare code. */
fun tokenFromInvite(linkOrCode: String) = linkOrCode.trim().substringAfterLast('/')
