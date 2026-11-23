package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One slot of a summary: a photo ([media]) or a text tile ([text] on [bg]). [noGrade] leaves
 * this photo out of colour harmonizing. All empty means an empty slot.
 */
@Serializable
data class SummaryTile(
    val media: String? = null,
    val text: String? = null,
    val bg: String? = null,
    val noGrade: Boolean = false,
)

@Serializable
data class Summary(
    val id: String? = null, // null until saved
    @SerialName("trip_id") val tripId: String,
    @SerialName("created_by") val createdBy: String? = null,
    val name: String,
    val layout: String,
    val mode: String,
    val tiles: List<SummaryTile>,
    val harmonize: Double = 0.0,
    val colours: List<String> = emptyList(),
)

@Serializable
private class SummaryWrite(
    @SerialName("trip_id") val tripId: String,
    val name: String,
    val layout: String,
    val mode: String,
    val tiles: List<SummaryTile>,
    val harmonize: Double,
    val colours: List<String>,
)

@Serializable
private class SummaryUpdate(
    val name: String,
    val layout: String,
    val mode: String,
    val tiles: List<SummaryTile>,
    val harmonize: Double,
    val colours: List<String>,
    @SerialName("updated_at") val updatedAt: String,
)

object Summaries {
    suspend fun ofTrip(tripId: String): List<Summary> =
        supabase.from("summaries").select { filter { eq("trip_id", tripId) }; order("updated_at", Order.DESCENDING) }.decodeList()

    suspend fun get(id: String): Summary =
        supabase.from("summaries").select { filter { eq("id", id) } }.decodeSingle()

    /** Saves a new summary or the changes to an existing one, and returns it as stored. */
    suspend fun save(s: Summary): Summary =
        if (s.id == null) {
            supabase.from("summaries")
                .insert(SummaryWrite(s.tripId, s.name.trim(), s.layout, s.mode, s.tiles, s.harmonize, s.colours)) { select() }
                .decodeSingle()
        } else {
            supabase.from("summaries")
                .update(SummaryUpdate(s.name.trim(), s.layout, s.mode, s.tiles, s.harmonize, s.colours, Clock.System.now().toString())) {
                    select()
                    filter { eq("id", s.id) }
                }.decodeSingle()
        }

    suspend fun delete(id: String) {
        supabase.from("summaries").delete { filter { eq("id", id) } }
    }
}
