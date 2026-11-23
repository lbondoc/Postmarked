package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.searchPlaces
import io.github.lbondoc.postmarked.domain.geo.Place
import kotlinx.datetime.LocalDate

private const val MS_PER_DAY = 86_400_000L
private const val MAX_REGIONS = 20 // the server's limit

class NewTripViewModel : ScreenModel() {
    /** null before the first search; an empty list means the search found nothing. */
    var results by mutableStateOf<List<Place>?>(null)
        private set
    val regions = mutableStateListOf<Place>()

    fun search(query: String) = run { results = searchPlaces(query.trim()) }

    fun add(place: Place) {
        if (place !in regions && regions.size < MAX_REGIONS) regions += place
        results = null
    }

    fun create(name: String, start: LocalDate, end: LocalDate, onCreated: (String) -> Unit) =
        run { onCreated(Trips.create(name.trim(), start, end, regions)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTripScreen(
    onBack: () -> Unit,
    onCreated: (tripId: String) -> Unit,
    vm: NewTripViewModel = viewModel { NewTripViewModel() },
) {
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var pickingDates by remember { mutableStateOf(false) }
    val picker = rememberDateRangePickerState()
    // Set once the trip exists: the stamp plays, then the trip opens.
    var created by remember { mutableStateOf<String?>(null) }
    // The picker reports midnight UTC in milliseconds; whole days since 1970 is the calendar date.
    val start = picker.selectedStartDateMillis?.let { LocalDate.fromEpochDays(it.floorDiv(MS_PER_DAY)) }
    val end = picker.selectedEndDateMillis?.let { LocalDate.fromEpochDays(it.floorDiv(MS_PER_DAY)) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("New trip") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(80) },
                label = { Text("Trip name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Text("When", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { pickingDates = true }) {
                Text(if (start != null && end != null) dateRange(start, end) else "Choose dates")
            }

            Text("Where", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add every area the trip passes through: cities, parks or whole states.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.regions.forEach { place ->
                InputChip(
                    selected = true,
                    onClick = { vm.regions -= place },
                    label = { Text(place.label.substringBefore(',')) },
                    trailingIcon = { Text("✕") },
                    modifier = Modifier.height(48.dp),
                )
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
                        supportingContent = { Text("Tap to add") },
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.add(place)
                            query = ""
                        },
                    )
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.create(name, start!!, end!!) { created = it } },
                // The trip needs all three: a name, a date range and at least one region.
                enabled = !vm.busy && name.isNotBlank() && start != null && end != null && vm.regions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create trip") }
        }
    }

    created?.let { id -> StampOverlay(onDone = { onCreated(id) }) }

    if (pickingDates) {
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = { TextButton(onClick = { pickingDates = false }) { Text("Done") } },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.height(480.dp))
        }
    }
}
