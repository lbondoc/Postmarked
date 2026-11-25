package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.data.searchPlaces
import io.github.lbondoc.postmarked.data.userMessage
import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.Place
import io.github.lbondoc.postmarked.domain.geo.centre
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.toInstant

/** "14:30" or "9:05" as a clock time; null for anything else, including blank. */
internal fun clockOf(text: String): LocalTime? =
    Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim())?.destructured?.let { (h, m) ->
        if (h.toInt() < 24 && m.toInt() < 60) LocalTime(h.toInt(), m.toInt()) else null
    }

/**
 * The moment a person chose: [time] (noon without one) on [day], read with the photo's own UTC offset when the
 * camera recorded one, else in [zone].
 */
fun momentOf(day: LocalDate, time: LocalTime?, utcOffsetMinutes: Int?, zone: TimeZone): Instant {
    val clock = LocalDateTime(day, time ?: LocalTime(12, 0))
    return if (utcOffsetMinutes != null) clock.toInstant(UtcOffset(minutes = utcOffsetMinutes)) else clock.toInstant(zone)
}

/**
 * Edit date and place (SPEC §9.3): a trip day with an optional time, and a place found by search and, where there
 * is a tiled map, fine-tuned by dragging it under a pin. The place can also be cleared. With [showDate] false only
 * the place is asked for (the import review, for photos with no location). [onSave] gets the chosen values.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DatePlaceDialog(
    title: String,
    tripDays: List<LocalDate>,
    day: LocalDate?,
    time: LocalTime?,
    place: LatLng?,
    showDate: Boolean,
    onSave: (day: LocalDate?, time: LocalTime?, place: LatLng?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var chosenDay by remember { mutableStateOf(day) }
    var clock by remember { mutableStateOf(time?.let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" } ?: "") }
    var spot by remember { mutableStateOf(place) }
    var picks by remember { mutableIntStateOf(0) } // a new search pick moves the map there
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val timeOk = clock.isBlank() || clockOf(clock) != null
    // The photo's current day stays offered even when it's outside the trip's dates.
    val days = (tripDays + listOfNotNull(day)).distinct().sorted()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showDate) {
                    SectionLabel("Day")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in days) {
                            val n = tripDays.indexOf(d)
                            InkChip(if (n >= 0) "Day ${n + 1} · ${d.format(DAY)}" else d.format(DAY), chosenDay == d, { chosenDay = d })
                        }
                    }
                    OutlinedTextField(
                        value = clock, onValueChange = { clock = it.take(5) }, singleLine = true,
                        label = { Text("Time (optional), e.g. 14:30") }, isError = !timeOk,
                        supportingText = { Text(if (timeOk) "Without a time it's placed at noon." else "Use hours:minutes, like 9:05 or 14:30.") },
                    )
                }
                SectionLabel("Place")
                Text(
                    spot?.let { "Set: ${(it.lat * 1e4).roundToInt() / 1e4}, ${(it.lng * 1e4).roundToInt() / 1e4}" } ?: "No place",
                    style = MaterialTheme.typography.bodyMedium,
                )
                spot?.let { at ->
                    if (hasPinPicker) {
                        key(picks) { PinPicker(at, onMove = { spot = it }, Modifier.fillMaxWidth().height(220.dp)) }
                        Text("Drag the map to put the pin on the spot.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { spot = null }) { Text("Clear place") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search for a place") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            problem = null
                            results = try {
                                searchPlaces(query.trim())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                problem = e.userMessage(); null
                            }
                        }
                    },
                    enabled = query.isNotBlank(),
                ) { Text("Search") }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                results?.let { found ->
                    if (found.isEmpty()) Text("Nothing found. Try a different spelling or a nearby town.")
                    for (p in found) TextButton(onClick = {
                        p.centre()?.let { spot = it; picks++ }
                        results = null
                        query = ""
                    }) { Text(p.label) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(chosenDay, clockOf(clock), spot) },
                enabled = timeOk && (!showDate || chosenDay != null),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
