package io.github.lbondoc.postmarked.ui

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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
) {
    val isEmpty get() = onTrip.isEmpty() && noLocation.isEmpty() && elsewhere.isEmpty() && otherDays.isEmpty()
}

fun sortForTrip(scan: ScanResult, trip: TripBounds): Found {
    val byMatch = scan.items.groupBy { matchTrip(it.takenOn, it.location, trip.start, trip.end, trip.area) }
    val (noLocation, elsewhere) = byMatch[TripMatch.DatesOnly].orEmpty().partition { it.location == null }
    return Found(byMatch[TripMatch.Both].orEmpty(), noLocation, elsewhere, byMatch[TripMatch.LocationOnly].orEmpty(), scan.locationAllowed)
}

class ImportViewModel(private val tripId: String) : ScreenModel() {
    var found by mutableStateOf<Found?>(null)
        private set
    var accessRefused by mutableStateOf(false)
        private set
    val selected = mutableStateListOf<String>() // camera-roll uris

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
        selected += result.onTrip.map { it.uri }.filterNot { it in alreadyAdded } // only clear matches start ticked
        found = result
    }

    fun toggle(uri: String) {
        if (!selected.remove(uri)) selected += uri
    }

    /** Everything shown, in screen order. */
    fun all(): List<DeviceMedia> = found?.run { onTrip + noLocation + elsewhere + otherDays }.orEmpty()

    fun chosen(): List<DeviceMedia> = all().filter { it.uri in selected }

    /** Ticks (or unticks) all of [items], skipping ones already added. */
    fun setAll(items: List<DeviceMedia>, on: Boolean, added: Set<String>) {
        val uris = items.map { it.uri }.filterNot { it in added }
        if (on) selected += uris.filterNot { it in selected } else selected.removeAll(uris.toSet())
    }

    /** During a press-and-drag: the selection from when it began ([base]), with [range] all set to [on]. */
    fun setRange(base: Set<String>, range: List<String>, on: Boolean) {
        val next = if (on) base + range else base - range.toSet()
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
                        enabled = vm.selected.isNotEmpty(),
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
                    modifier = Modifier.pointerInput(found, added) {
                        val order = vm.all().map { it.uri }.filterNot { it in added }
                        var anchor = -1
                        var on = true
                        var base = emptySet<String>()
                        var finger = Offset.Zero
                        var edgeScroll: Job? = null
                        fun indexAt(at: Offset) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                            at.x >= info.offset.x && at.x < info.offset.x + info.size.width &&
                                at.y >= info.offset.y && at.y < info.offset.y + info.size.height
                        }?.let { order.indexOf(it.key) }?.takeIf { it >= 0 }
                        fun tickTo(at: Offset) {
                            val here = indexAt(at) ?: return
                            vm.setRange(base, order.subList(minOf(anchor, here), maxOf(anchor, here) + 1), on)
                        }
                        val edge = 72.dp.toPx()
                        val fastest = 24.dp.toPx() // per frame, with the finger right at the edge
                        fun stop() { anchor = -1; edgeScroll?.cancel(); edgeScroll = null }
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at ->
                                anchor = indexAt(at) ?: -1
                                if (anchor >= 0) {
                                    on = order[anchor] !in vm.selected // starting on a ticked photo unticks
                                    base = vm.selected.toSet()
                                    finger = at
                                    // Holding near the top or bottom edge scrolls, faster the closer to the edge, ticking as it goes.
                                    edgeScroll = scope.launch {
                                        while (isActive) {
                                            val speed = when {
                                                finger.y < edge -> -(edge - finger.y) / edge * fastest
                                                finger.y > size.height - edge -> (finger.y - (size.height - edge)) / edge * fastest
                                                else -> 0f
                                            }
                                            if (speed != 0f && gridState.scrollBy(speed) != 0f) tickTo(finger)
                                            delay(16)
                                        }
                                    }
                                }
                            },
                            onDrag = { change, _ ->
                                if (anchor < 0) return@detectDragGesturesAfterLongPress
                                change.consume()
                                finger = change.position
                                tickTo(finger)
                            },
                            onDragEnd = { stop() },
                            onDragCancel = { stop() },
                        )
                    },
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
                    section("Taken on this trip", null, found.onTrip, vm, added)
                    section("No location saved", "Taken during the trip, but the camera didn't record where. Tick the ones from the trip.", found.noLocation, vm, added)
                    section("Taken somewhere else", "Taken during the trip's dates, but away from its places.", found.elsewhere, vm, added)
                    section("Taken on other days", "Taken at the trip's places, but outside its dates.", found.otherDays, vm, added)
                }
            }
        }
    }
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

private fun LazyGridScope.section(title: String, explanation: String?, items: List<DeviceMedia>, vm: ImportViewModel, added: Set<String>) {
    if (items.isEmpty()) return
    val selectable = items.filterNot { it.uri in added }
    header("$title (${items.size})", explanation) {
        if (selectable.isNotEmpty()) {
            val allOn = selectable.all { it.uri in vm.selected }
            TextButton(onClick = { vm.setAll(selectable, on = !allOn, added) }) { Text(if (allOn) "Clear" else "Select all") }
        }
    }
    items(items, key = { it.uri }) { item ->
        Cell(item, selected = item.uri in vm.selected, added = item.uri in added, onToggle = { vm.toggle(item.uri) })
    }
}

@Composable
private fun Cell(item: DeviceMedia, selected: Boolean, added: Boolean, onToggle: () -> Unit) {
    val kind = if (item.kind == MediaKind.Video) "Video" else "Photo"
    Box(
        Modifier.aspectRatio(1f)
            .toggleable(value = selected, enabled = !added, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = "$kind from ${item.takenOn}" + if (added) ", already added" else ""
                toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
            }
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        // Coil loads straight from the camera roll's content:// address, decoding at grid size.
        AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.kind == MediaKind.Video) Badge("▶ Video", Modifier.align(Alignment.BottomStart))
        if (selected) Badge("✓", Modifier.align(Alignment.TopEnd))
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
