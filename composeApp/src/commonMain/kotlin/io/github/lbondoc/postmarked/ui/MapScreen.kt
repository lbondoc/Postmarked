package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.lbondoc.postmarked.data.placeName
import io.github.lbondoc.postmarked.domain.geo.LatLng
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.Routes
import io.github.lbondoc.postmarked.data.TripRoute
import io.github.lbondoc.postmarked.data.Trips
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format

private const val METERS_PER_MILE = 1609.344

// One colour per day of the trip, repeating after eight days.
private val DAY_COLORS = listOf("#B5452F", "#2E7D7A", "#C7922B", "#3B4F7A", "#6F7D3C", "#7A4767", "#D06A3A", "#5C6670")

internal fun dayNumber(start: LocalDate, day: LocalDate) = start.daysUntil(day) + 1
internal fun dayColor(number: Int) = DAY_COLORS[(number - 1).mod(DAY_COLORS.size)]
internal fun parseHex(hex: String) = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)

class MapViewModel(private val tripId: String) : ScreenModel() {
    var route by mutableStateOf<TripRoute?>(null)
        private set
    var start by mutableStateOf<LocalDate?>(null)
        private set
    var thumbnails by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun load() = run {
        start = Trips.byId(tripId).startDate
        val built = Routes.build(tripId)
        thumbnails = Media.thumbnailUrls(tripId, built.media.keys.toList())
        route = built
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(tripId: String, onBack: () -> Unit, vm: MapViewModel = viewModel { MapViewModel(tripId) }) {
    var openStop by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { if (vm.route == null) vm.load() }
    val route = vm.route
    val start = vm.start

    Scaffold(
        topBar = { TopAppBar(title = { Text("Route") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                vm.error != null -> Text(vm.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                route == null || start == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                route.plan.stops.isEmpty() -> Text(
                    "No photos with a location yet. Add some from the trip page and the route appears here.",
                    modifier = Modifier.padding(24.dp),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    TripMap(route, start, onStop = { openStop = it }, modifier = Modifier.weight(0.6f))
                    Summary(route, start, Modifier.weight(0.4f))
                }
            }
        }
    }

    val stopNames = rememberStopNames(remember(route) { route?.plan?.stops?.map { it.at }.orEmpty() })
    val stop = openStop?.let { route?.plan?.stops?.getOrNull(it) }
    if (stop != null && route != null) {
        ModalBottomSheet(onDismissRequest = { openStop = null }) {
            Text(
                "${stopTitle(openStop!!, stopNames)} · ${stop.ids.size} photos and videos",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(stop.ids.mapNotNull { route.media[it] }, key = { it.id }) { item ->
                    Thumbnail(tripId, item.id, item.kind, vm.thumbnails[item.id])
                }
            }
        }
    }
}

@Composable
private fun Summary(route: TripRoute, start: LocalDate, modifier: Modifier) {
    val miles = route.days.sumOf { it.meters } / METERS_PER_MILE
    val seconds = route.days.sumOf { it.seconds }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("${miles.roundToInt()} mi · ${duration(seconds)} driving", style = MaterialTheme.typography.titleLarge)
            Text("${route.plan.stops.size} stops over ${route.days.size} days")
            if (route.days.any { it.approximate }) Text(
                "Some legs are straight lines, so their miles are short of the real drive" +
                    (route.routingProblem?.let { ": $it." } ?: "."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        items(route.days, key = { it.day.toString() }) { day ->
            val number = dayNumber(start, day.day)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(14.dp).background(parseHex(dayColor(number)), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text("Day $number · ${day.day.format(DAY)}")
                    Text(
                        "${(day.meters / METERS_PER_MILE).roundToInt()} mi" +
                            (if (day.seconds > 0) " · ${duration(day.seconds)}" else "") +
                            (if (day.approximate) " · approximate" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Place names for [stops], by position, filling in as each lookup finishes; unnamed ones stay numbered. */
@Composable
internal fun rememberStopNames(stops: List<LatLng>): Map<Int, String> {
    var names by remember(stops) { mutableStateOf(emptyMap<Int, String>()) }
    LaunchedEffect(stops) {
        stops.forEachIndexed { i, at -> placeName(at)?.let { names = names + (i to it) } }
    }
    return names
}

private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}
