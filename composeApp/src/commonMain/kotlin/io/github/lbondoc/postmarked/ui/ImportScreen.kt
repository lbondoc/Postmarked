package io.github.lbondoc.postmarked.ui

import kotlinx.datetime.toInstant
import kotlinx.datetime.format
import kotlinx.datetime.LocalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import io.github.lbondoc.postmarked.data.suggestedDay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.ScanResult
import io.github.lbondoc.postmarked.data.TripBounds
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.domain.trip.TripMatch
import io.github.lbondoc.postmarked.domain.trip.matchTrip
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// shortcut: photos taken at the trip's places are only looked for within this many days of it,
// because reading GPS from the whole camera roll is slow; widen it if people miss photos.
private const val LOOK_AROUND_DAYS = 3

/** The camera-roll items worth showing, sorted into what the user sees as sections. */
class Found(
    val onTrip: List<DeviceMedia>,
    val noLocation: List<DeviceMedia>,
    val elsewhere: List<DeviceMedia>,
    val otherDays: List<DeviceMedia>,
    val locationAllowed: Boolean,
    /** Files with no date of their own; each needs a trip day chosen before it can be added (SPEC §9.2). */
    val noDate: List<DeviceMedia> = emptyList(),
) {
    val isEmpty get() = onTrip.isEmpty() && noLocation.isEmpty() && elsewhere.isEmpty() && otherDays.isEmpty() && noDate.isEmpty()
}

/**
 * Which items start ticked: on a phone only clear matches ("taken on this trip"), since the scan covers the whole
 * camera roll around the trip. Files picked by hand (the website) were all chosen on purpose, so all of them,
 * wherever their dates and GPS (often missing from photos copied to a computer) sorted them.
 */
fun startTicked(found: Found, alreadyAdded: Set<String>, pickedByHand: Boolean): List<String> =
    (if (pickedByHand) found.onTrip + found.noLocation + found.elsewhere + found.otherDays + found.noDate else found.onTrip)
        .map { it.uri }.filterNot { it in alreadyAdded }

fun sortForTrip(scan: ScanResult, trip: TripBounds): Found {
    // A file with no date can't be matched by date: it waits for the person to choose its day.
    val (dated, undated) = scan.items.partition { it.dateKnown }
    val byMatch = dated.groupBy { matchTrip(it.takenOn, it.location, trip.start, trip.end, trip.area) }
    val (noLocation, elsewhere) = byMatch[TripMatch.DatesOnly].orEmpty().partition { it.location == null }
    return Found(byMatch[TripMatch.Both].orEmpty(), noLocation, elsewhere, byMatch[TripMatch.LocationOnly].orEmpty(), scan.locationAllowed, undated)
}

/** Add is allowed once something is ticked and every ticked file with no date has a day in [days] (by uri). */
fun readyToAdd(chosen: List<DeviceMedia>, days: Map<String, LocalDate>): Boolean =
    chosen.isNotEmpty() && chosen.all { it.dateKnown || it.uri in days }

/**
 * A file with no date, dated as the person chose: [time] on [day] in [zone], noon when no time was given. The camera
 * recorded no time zone, so none is stored; the day is what matters for the gallery and route.
 */
fun withChosenDay(item: DeviceMedia, day: LocalDate, time: LocalTime?, zone: TimeZone): DeviceMedia = item.copy(
    capturedAtMs = LocalDateTime(day, time ?: LocalTime(12, 0)).toInstant(zone).toEpochMilliseconds(),
    takenOn = day,
    utcOffsetMinutes = null,
    dateByHand = true,
)

class ImportViewModel(private val tripId: String) : ScreenModel() {
    var found by mutableStateOf<Found?>(null)
        private set
    var accessRefused by mutableStateOf(false)
        private set
    val selected = mutableStateListOf<String>() // camera-roll uris
    /** The trip day chosen for each file with no date, and the optional time, by uri. */
    val days = mutableStateMapOf<String, LocalDate>()
    val times = mutableStateMapOf<String, LocalTime>()
    var tripDays by mutableStateOf<List<LocalDate>>(emptyList())
        private set

    /** [cameraRoll] is passed in rather than kept: it belongs to the Activity, which outlives this only until rotation. */
    fun load(cameraRoll: CameraRoll, alreadyAdded: Set<String>) = run {
        accessRefused = !cameraRoll.requestAccess()
        if (accessRefused) return@run
        val trip = Trips.bounds(tripId)
        val zone = TimeZone.currentSystemDefault()
        val from = trip.start.minus(LOOK_AROUND_DAYS, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val until = trip.end.plus(LOOK_AROUND_DAYS + 1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        val result = sortForTrip(cameraRoll.scan(from.toEpochMilliseconds(), until.toEpochMilliseconds()), trip)
        selected.clear()
        selected += startTicked(result, alreadyAdded, cameraRoll.pickedByHand)
        tripDays = generateSequence(trip.start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= trip.end }.toList()
        days.clear()
        for (item in result.noDate) suggestedDay(item, trip.start, trip.end)?.let { days[item.uri] = it }
        found = result
    }

    fun toggle(uri: String) {
        if (!selected.remove(uri)) selected += uri
    }

    /** Everything shown, in screen order. */
    fun all(): List<DeviceMedia> = found?.run { noDate + onTrip + noLocation + elsewhere + otherDays }.orEmpty()

    /** The ticked items as they are, before any chosen days are applied. */
    fun ticked(): List<DeviceMedia> = all().filter { it.uri in selected }

    /** The ticked items ready to upload, files with no date carrying their chosen day. */
    fun chosen(): List<DeviceMedia> = ticked().map { item ->
        days[item.uri]?.takeIf { !item.dateKnown }?.let { withChosenDay(item, it, times[item.uri], TimeZone.currentSystemDefault()) } ?: item
    }

    /** Gives every one of [items] the trip [day], and [time] when given. */
    fun setDay(items: List<DeviceMedia>, day: LocalDate, time: LocalTime?) {
        for (item in items) {
            days[item.uri] = day
            if (time != null) times[item.uri] = time else times.remove(item.uri)
        }
    }

    /** Ticks (or unticks) all of [items], skipping ones already added. */
    fun setAll(items: List<DeviceMedia>, on: Boolean, added: Set<String>) {
        val uris = items.map { it.uri }.filterNot { it in added }
        if (on) selected += uris.filterNot { it in selected } else selected.removeAll(uris.toSet())
    }

    /** During a press-and-drag: the selection from when it began ([base]), with [range] all set to [on]. */
    fun setRange(base: Set<String>, range: List<String>, on: Boolean) {
        val next = selectRange(base, range, on)
        selected.retainAll(next)
        selected += next.filterNot { it in selected }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    tripId: String,
    cameraRoll: CameraRoll,
    onDone: () -> Unit,
    vm: ImportViewModel = viewModel { ImportViewModel(tripId) },
) {
    val uploads by cameraRoll.uploads.collectAsState()
    val mobileData by cameraRoll.mobileData.collectAsState()
    // Failed items don't count as added, so they can be picked again.
    val added = uploads.filter { it.tripId == tripId && it.problem == null }.map { it.item.uri }.toSet()

    LaunchedEffect(Unit) { if (vm.found == null) vm.load(cameraRoll, added) }
    // The ticked files with no date the day picker is open for.
    var choosingDayFor by remember { mutableStateOf<List<DeviceMedia>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add photos and videos") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
                actions = {
                    val selectable = vm.all().filterNot { it.uri in added }
                    if (selectable.isNotEmpty()) {
                        val allOn = selectable.all { it.uri in vm.selected }
                        TextButton(onClick = { vm.setAll(selectable, on = !allOn, added) }) { Text(if (allOn) "Clear all" else "Select all") }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.found?.isEmpty == false) Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f).toggleable(mobileData, role = Role.Switch, onValueChange = cameraRoll::allowMobileData),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(checked = mobileData, onCheckedChange = null)
                        Text("Use mobile data", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = {
                            cameraRoll.upload(tripId, vm.chosen())
                            onDone()
                        },
                        enabled = readyToAdd(vm.ticked(), vm.days),
                    ) { Text("Add ${vm.selected.size}") }
                }
            }
        },
    ) { insets ->
        val found = vm.found
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                vm.accessRefused -> Message("Postmarked needs access to your photos to add them. Allow it when asked, or in the app's settings.") {
                    TextButton(onClick = { vm.load(cameraRoll, added) }) { Text("Try again") }
                }
                vm.error != null -> Message(vm.error!!) { TextButton(onClick = { vm.load(cameraRoll, added) }) { Text("Try again") } }
                found == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                found.isEmpty -> Message("No photos or videos from this trip's dates are on this phone.")
                else -> LazyVerticalGrid(
                    state = gridState,
                    // Press and hold on a photo, then drag across others to tick (or untick) the whole run.
                    modifier = Modifier.dragSelect(
                        gridState, scope, key = found to added,
                        order = { vm.all().map { it.uri }.filterNot { it in added } },
                        selected = { vm.selected.toSet() },
                        onRange = vm::setRange,
                    ),
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!found.locationAllowed) header(
                        "Location is off",
                        "Postmarked wasn't allowed to read where these were taken, so it can only go by dates. " +
                            "Allow \"media location\" in the app's settings for better matches.",
                    )
                    section(
                        "No date in the file",
                        "These don't say when they were taken. Choose the trip day for each ticked one; the time is optional.",
                        found.noDate, vm, added, cameraRoll::preview,
                        dayOf = { item -> vm.days[item.uri]?.let { "Day ${vm.tripDays.indexOf(it) + 1}" } ?: "No day" },
                        action = { items -> TextButton(onClick = { choosingDayFor = items }) { Text("Set day") } },
                    )
                    section("Taken on this trip", null, found.onTrip, vm, added, cameraRoll::preview)
                    section("No location saved", "Taken during the trip, but the camera didn't record where. Tick the ones from the trip.", found.noLocation, vm, added, cameraRoll::preview)
                    section("Taken somewhere else", "Taken during the trip's dates, but away from its places.", found.elsewhere, vm, added, cameraRoll::preview)
                    section("Taken on other days", "Taken at the trip's places, but outside its dates.", found.otherDays, vm, added, cameraRoll::preview)
                }
            }
        }
    }
    choosingDayFor?.let { items ->
        DayPicker(vm.tripDays, items.size, onPick = { day, time -> vm.setDay(items, day, time); choosingDayFor = null }, onDismiss = { choosingDayFor = null })
    }
}

/** Picks one trip day (and optionally a time, "14:30") for [items], files with no date. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(tripDays: List<LocalDate>, count: Int, onPick: (LocalDate, LocalTime?) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf("") }
    val parsed = time.trim().takeIf { it.isNotEmpty() }?.let { t ->
        Regex("^(\\d{1,2}):(\\d{2})$").find(t)?.destructured?.let { (h, m) -> h.toInt().takeIf { it < 24 }?.let { hh -> m.toInt().takeIf { it < 60 }?.let { LocalTime(hh, it) } } }
    }
    val timeOk = time.isBlank() || parsed != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Which day was it taken?" else "Which day were these $count taken?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tripDays.forEachIndexed { i, d -> InkChip("Day ${i + 1} · ${d.format(DAY)}", day == d, { day = d }) }
                }
                OutlinedTextField(
                    value = time, onValueChange = { time = it.take(5) }, singleLine = true,
                    label = { Text("Time (optional), e.g. 14:30") }, isError = !timeOk,
                    supportingText = { Text(if (timeOk) "Without a time it's placed at noon." else "Use hours:minutes, like 9:05 or 14:30.") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(day!!, parsed) }, enabled = day != null && timeOk) { Text("Set day") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun LazyGridScope.header(title: String, explanation: String?, action: (@Composable () -> Unit)? = null) {
    // maxLineSpan: the header takes the whole row instead of one grid cell.
    item(span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            action?.invoke()
        }
    }
}

private fun LazyGridScope.section(
    title: String,
    explanation: String?,
    items: List<DeviceMedia>,
    vm: ImportViewModel,
    added: Set<String>,
    preview: (DeviceMedia) -> Any?,
    /** A label on each cell, e.g. the chosen day of a file with no date. */
    dayOf: ((DeviceMedia) -> String)? = null,
    /** An extra header action for the section's ticked items. */
    action: (@Composable (ticked: List<DeviceMedia>) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val selectable = items.filterNot { it.uri in added }
    header("$title (${items.size})", explanation) {
        val ticked = selectable.filter { it.uri in vm.selected }
        if (action != null && ticked.isNotEmpty()) action(ticked)
        if (selectable.isNotEmpty()) {
            val allOn = selectable.all { it.uri in vm.selected }
            TextButton(onClick = { vm.setAll(selectable, on = !allOn, added) }) { Text(if (allOn) "Clear" else "Select all") }
        }
    }
    items(items, key = { it.uri }) { item ->
        Cell(item, preview(item), selected = item.uri in vm.selected, added = item.uri in added, onToggle = { vm.toggle(item.uri) }, label = dayOf?.invoke(item))
    }
}

@Composable
private fun Cell(item: DeviceMedia, preview: Any?, selected: Boolean, added: Boolean, onToggle: () -> Unit, label: String? = null) {
    val kind = if (item.kind == MediaKind.Video) "Video" else "Photo"
    Box(
        Modifier.aspectRatio(1f)
            .toggleable(value = selected, enabled = !added, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = (if (label != null) "$kind with no date, $label" else "$kind from ${item.takenOn}") + if (added) ", already added" else ""
                toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
            }
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        // On the phone Coil loads straight from the camera roll's content:// address, decoding at grid size.
        AsyncImage(model = preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.kind == MediaKind.Video) Badge("▶ Video", Modifier.align(Alignment.BottomStart))
        if (selected) Badge("✓", Modifier.align(Alignment.TopEnd))
        label?.let { Badge(it, Modifier.align(Alignment.TopStart)) }
        if (added) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), Alignment.Center) {
            Text("Added", color = Color.White)
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text)
        action()
    }
}
