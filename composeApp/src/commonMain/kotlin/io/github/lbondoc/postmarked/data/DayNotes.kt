package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One day's note (SPEC §6). Writes go through the [Outbox], so they work offline and the newest edit wins. */
@Serializable
data class DayNote(val day: LocalDate, val body: String, @SerialName("updated_at") val updatedAt: String)

object DayNotes {
    suspend fun ofTrip(tripId: String): List<DayNote> =
        supabase.from("day_notes").select(Columns.list("day", "body", "updated_at")) { filter { eq("trip_id", tripId) } }
            .decodeList()
}

/**
 * The notes to show: what the server has, with edits still waiting in the outbox laid over them (an empty
 * edit removes the note from view). Saved and queued notes both count, so an offline edit shows at once.
 */
fun notesToShow(saved: List<DayNote>, outbox: List<OutboxEntry>, tripId: String): Map<LocalDate, String> {
    val shown = saved.associate { it.day to it.body }.toMutableMap()
    for (edit in outbox.filterIsInstance<OutboxEntry.DayNote>().filter { it.tripId == tripId }) {
        if (edit.body.isBlank()) shown.remove(edit.day) else shown[edit.day] = edit.body
    }
    return shown
}
