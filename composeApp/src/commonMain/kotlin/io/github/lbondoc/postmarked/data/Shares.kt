package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the website lives; share links open its read-only view (SPEC §7). */
const val SITE_URL = "https://lbondoc.github.io/Postmarked/"

fun shareLink(token: String) = "$SITE_URL#/s/$token"

/** The token in a `#/s/<token>` address, or null when the address isn't a share link. */
fun shareTokenOf(hash: String): String? = Regex("^#/s/([A-Za-z0-9_-]{22})$").find(hash)?.groupValues?.get(1)

/** What the `share` Edge Function returns for a live link. */
@Serializable
data class SharedTrip(val trip: SharedTripInfo, val notes: List<SharedNote> = emptyList(), val media: List<SharedMedia> = emptyList())

@Serializable
data class SharedTripInfo(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate,
)

@Serializable
data class SharedNote(val day: LocalDate, val body: String)

/** A media row as shared: no contributor id, and signed links to the small files only. */
@Serializable
data class SharedMedia(
    val id: String,
    val kind: MediaKind,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("utc_offset_minutes") val utcOffsetMinutes: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val profile: Profile? = null,
    val thumb: String? = null,
    val display: String? = null,
    val poster: String? = null,
) {
    /** As a trip item, so the gallery's day and time rules apply unchanged. */
    fun asTripMedia() = TripMedia(id, kind, contributor = "", capturedAt, utcOffsetMinutes, lat, lng, profile = profile)
}

object Shares {
    private val json = Json { ignoreUnknownKeys = true }

    /** The trip behind a share link; fails with the server's message for a dead link. Needs no sign-in. */
    suspend fun open(token: String): SharedTrip =
        json.decodeFromString(supabase.functions.invoke("share", buildJsonObject { put("token", token) }).bodyAsText())

    /** Whether the trip has a live share link (any member may ask). */
    suspend fun isOn(tripId: String): Boolean =
        supabase.postgrest.rpc("share_is_on", buildJsonObject { put("p_trip", tripId) }).decodeAs()

    /** The live link's token when I made it; null when sharing is off or another editor made the link. */
    suspend fun myToken(tripId: String): String? =
        supabase.from("trip_shares").select(Columns.list("token")) {
            filter { eq("trip_id", tripId); exact("revoked_at", null) }
        }.decodeList<TokenRow>().firstOrNull()?.token

    /** Turns sharing on with a new token, replacing any live link. Owner and editors only. */
    suspend fun create(tripId: String): String =
        supabase.postgrest.rpc("create_share", buildJsonObject { put("p_trip", tripId) }).decodeAs()

    suspend fun revoke(tripId: String) {
        supabase.postgrest.rpc("revoke_share", buildJsonObject { put("p_trip", tripId) })
    }

    @Serializable
    private class TokenRow(val token: String)
}
