package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lbondoc.postmarked.data.TripPoint
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.searchPlaces
import io.github.lbondoc.postmarked.domain.geo.Place
import io.github.lbondoc.postmarked.domain.geo.centre
import kotlinx.datetime.LocalDate

private const val MS_PER_DAY = 86_400_000L
private const val MAX_REGIONS = 20 // the server's limit

/** New trip, or Edit trip when [editing] is a trip id: the form starts with that trip's values (SPEC §9.1). */
class NewTripViewModel(private val editing: String? = null) : ScreenModel() {
    /** null before the first search; an empty list means the search found nothing. */
    var results by mutableStateOf<List<Place>?>(null)
        private set
    var form by mutableStateOf(TripForm())
    /** The trip as loaded, when editing; null while it loads and for a new trip. */
    var original by mutableStateOf<TripForm?>(null)
        private set

    fun load() {
        val id = editing ?: return
        run {
            val loaded = Trips.forEditing(id).toForm()
            original = loaded
            form = loaded
        }
    }

    fun search(query: String) = run { results = searchPlaces(query.trim()) }

    fun add(place: Place) {
        if (place !in form.regions && form.regions.size < MAX_REGIONS) form = form.copy(regions = form.regions + place)
        results = null
    }

    /** A searched place as the trip's start ([isStart]) or end, at its centre. */
    fun setEnd(place: Place, isStart: Boolean) {
        val at = place.centre() ?: return
        val point = TripPoint(place.label.substringBefore(',').take(120), at.lat, at.lng)
        form = if (isStart) form.copy(startPoint = point) else form.copy(endPoint = point)
        results = null
    }

    fun create(onCreated: (String) -> Unit) = run {
        val f = form
        onCreated(Trips.create(f.name.trim(), f.start!!, f.end!!, f.regions, f.startPoint, f.endPoint))
    }

    /** Saves only when something changed; [onSaved] either way. */
    fun save(onSaved: () -> Unit) = run {
        val id = editing ?: return@run
        val edit = original?.let { tripEdit(it, form) }
        if (edit != null) Trips.edit(id, edit.name, edit.start!!, edit.end!!, edit.regions, edit.startPoint, edit.endPoint)
        onSaved()
    }
}

/** The Edit trip form: [NewTripScreen] filled in with the trip. Saving goes [onBack] to the trip, which reloads. */
@Composable
fun EditTripScreen(tripId: String, onBack: () -> Unit) =
    NewTripScreen(onBack = onBack, onCreated = {}, vm = viewModel(key = "edit-$tripId") { NewTripViewModel(tripId) }, editing = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTripScreen(
    onBack: () -> Unit,
    onCreated: (tripId: String) -> Unit,
    vm: NewTripViewModel = viewModel { NewTripViewModel() },
    editing: Boolean = false,
) {
    LaunchedEffect(Unit) { vm.load() }
    var query by rememberSaveable { mutableStateOf("") }
    var pickingDates by remember { mutableStateOf(false) }
    // Set once the trip exists: the stamp plays, then the trip opens.
    var created by remember { mutableStateOf<String?>(null) }
    val form = vm.form

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (editing) "Edit trip" else "New trip") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
    ) { insets ->
        if (editing && vm.original == null) {
            Column(Modifier.fillMaxSize().padding(insets).padding(16.dp)) {
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name, onValueChange = { vm.form = form.copy(name = it.take(80)) },
                label = { Text("Trip name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Text("When", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { pickingDates = true }) {
                Text(if (form.start != null && form.end != null) dateRange(form.start, form.end) else "Choose dates")
            }

            Text("Where", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add every area the trip passes through: cities, parks or whole states. These decide which photos are suggested.",
                style = MaterialTheme.typography.bodySmall,
            )
            form.regions.forEach { place ->
                InputChip(
                    selected = true,
                    onClick = { vm.form = form.copy(regions = form.regions - place) },
                    label = { Text(place.label.substringBefore(',')) },
                    trailingIcon = { Text("✕") },
                    modifier = Modifier.height(48.dp),
                )
            }
            for ((label, point, isStart) in listOf(Triple("Start", form.startPoint, true), Triple("End", form.endPoint, false))) {
                point?.let {
                    InputChip(
                        selected = true,
                        onClick = { vm.form = if (isStart) form.copy(startPoint = null) else form.copy(endPoint = null) },
                        label = { Text("$label: ${it.label}") },
                        trailingIcon = { Text("✕") },
                        modifier = Modifier.height(48.dp),
                    )
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search for a place") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.search(query) }),
            )
            OutlinedButton(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !vm.busy) { Text("Search") }
            vm.results?.let { results ->
                if (results.isEmpty()) Text("Nothing found. Try a different spelling or a nearby town.")
                results.forEach { place ->
                    ListItem(
                        headlineContent = { Text(place.label) },
                        supportingContent = { Text("Tap to add as an area") },
                        trailingContent = {
                            // Optional: where the drive began and ended.
                            Row {
                                TextButton(onClick = { vm.setEnd(place, isStart = true); query = "" }) { Text("Start") }
                                TextButton(onClick = { vm.setEnd(place, isStart = false); query = "" }) { Text("End") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.add(place)
                            query = ""
                        },
                    )
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { if (editing) vm.save(onBack) else vm.create { created = it } },
                // The trip needs all three: a name, a date range and at least one region.
                enabled = !vm.busy && form.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (editing) "Save changes" else "Create trip") }
        }
    }

    created?.let { id -> StampOverlay(onDone = { onCreated(id) }) }

    if (pickingDates) {
        // The picker reports midnight UTC in milliseconds; whole days since 1970 is the calendar date.
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = form.start?.toEpochDays()?.times(MS_PER_DAY),
            initialSelectedEndDateMillis = form.end?.toEpochDays()?.times(MS_PER_DAY),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.form = form.copy(
                        start = picker.selectedStartDateMillis?.let { LocalDate.fromEpochDays(it.floorDiv(MS_PER_DAY)) },
                        end = picker.selectedEndDateMillis?.let { LocalDate.fromEpochDays(it.floorDiv(MS_PER_DAY)) },
                    )
                    pickingDates = false
                }) { Text("Done") }
            },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.height(480.dp))
        }
    }
}
