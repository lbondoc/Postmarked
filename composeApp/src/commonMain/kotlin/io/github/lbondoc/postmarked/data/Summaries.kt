package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject
import io.github.lbondoc.postmarked.domain.color.Adjustment
import io.github.lbondoc.postmarked.domain.color.Filter
import io.github.lbondoc.postmarked.domain.summary.CropRatio
import io.github.lbondoc.postmarked.domain.color.Look
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One slot of a summary: a photo ([media]) or a text tile ([text] on [bg]). [noGrade] leaves
 * this photo out of colour harmonizing. All empty means an empty slot. A photo can have its own
 * [crop] and [look]; the look adds to the summary's unless [ownLook] says to ignore that.
 */
@Serializable
data class SummaryTile(
    val media: String? = null,
    val text: String? = null,
    val bg: String? = null,
    val noGrade: Boolean = false,
    val crop: Crop? = null,
    val look: LookData? = null,
    val ownLook: Boolean = false,
)

/**
 * Which part of a photo a tile shows: centred on ([x], [y]), fractions of the photo, [zoom] times closer. [ratio]
 * is the tile's shape in adaptive layouts, saved by name (a [CropRatio]); missing means Original.
 */
@Serializable
data class Crop(val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0, val ratio: String? = null) {
    val cropRatio: CropRatio get() = CropRatio.named(ratio)
}

/**
 * A filter and adjustments as saved: names rather than the app's own types, so a name a newer
 * version adds is simply ignored by an older one instead of failing to load.
 */
@Serializable
data class LookData(val filter: String = Filter.Original.name, val strength: Double = 1.0, val adjust: Map<String, Double> = emptyMap()) {
    fun toLook() = Look(
        filter = Filter.entries.firstOrNull { it.name == filter } ?: Filter.Original,
        strength = strength,
        adjust = adjust.mapNotNull { (name, v) -> Adjustment.entries.firstOrNull { it.name == name }?.let { it to v } }.toMap(),
    )
}

fun Look.toData() = LookData(filter.name, strength, adjust.filterValues { it != 0.0 }.mapKeys { it.key.name })

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
    /** The filter and adjustments for every photo in the summary. */
    val look: LookData? = null,
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
    val look: LookData?,
)

@Serializable
private class SummaryUpdate(
    val name: String,
    val layout: String,
    val mode: String,
    val tiles: List<SummaryTile>,
    val harmonize: Double,
    val colours: List<String>,
    val look: LookData?,
    @SerialName("updated_at") val updatedAt: String,
)

object Summaries {
    suspend fun ofTrip(tripId: String): List<Summary> =
        supabase.from("summaries").select { filter { eq("trip_id", tripId) }; order("updated_at", Order.DESCENDING) }.decodeList()

    /** Whether [userId] has made a summary on any trip they can see. */
    suspend fun anyBy(userId: String): Boolean =
        supabase.from("summaries").select(Columns.list("id")) { filter { eq("created_by", userId) }; limit(1) }.decodeList<JsonObject>().isNotEmpty()

    suspend fun get(id: String): Summary =
        supabase.from("summaries").select { filter { eq("id", id) } }.decodeSingle()

    /** Saves a new summary or the changes to an existing one, and returns it as stored. */
    suspend fun save(s: Summary): Summary =
        if (s.id == null) {
            supabase.from("summaries")
                .insert(SummaryWrite(s.tripId, s.name.trim(), s.layout, s.mode, s.tiles, s.harmonize, s.colours, s.look)) { select() }
                .decodeSingle()
        } else {
            supabase.from("summaries")
                .update(SummaryUpdate(s.name.trim(), s.layout, s.mode, s.tiles, s.harmonize, s.colours, s.look, Clock.System.now().toString())) {
                    select()
                    filter { eq("id", s.id) }
                }.decodeSingle()
        }

    suspend fun delete(id: String) {
        supabase.from("summaries").delete { filter { eq("id", id) } }
    }
}
