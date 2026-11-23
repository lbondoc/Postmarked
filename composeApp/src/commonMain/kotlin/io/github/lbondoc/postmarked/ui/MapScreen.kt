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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.Routes
import io.github.lbondoc.postmarked.data.TripRoute
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.domain.route.Stop
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox

// A free, keyless OpenStreetMap style in muted greys, so routes and pins stand out.
// shortcut: OpenFreeMap's stock style, replace with Postmarked's own vintage style in the theming milestone.
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val METERS_PER_MILE = 1609.344

// One colour per day of the trip, repeating after eight days.
private val DAY_COLORS = listOf("#B5452F", "#2E7D7A", "#C7922B", "#3B4F7A", "#6F7D3C", "#7A4767", "#D06A3A", "#5C6670")

private fun dayNumber(start: LocalDate, day: LocalDate) = start.daysUntil(day) + 1
private fun dayColor(number: Int) = DAY_COLORS[(number - 1).mod(DAY_COLORS.size)]
private fun parseHex(hex: String) = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)

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
                    RouteMap(route, start, onStop = { openStop = it }, modifier = Modifier.weight(0.6f))
                    Summary(route, start, Modifier.weight(0.4f))
                }
            }
        }
    }

    val stop = openStop?.let { route?.plan?.stops?.getOrNull(it) }
    if (stop != null && route != null) {
        ModalBottomSheet(onDismissRequest = { openStop = null }) {
            Text(
                "Stop ${openStop!! + 1} · ${stop.ids.size} photos and videos",
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
private fun RouteMap(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier) {
    val lines = remember(route) { linesGeoJson(route, start) }
    val stops = remember(route) { stopsGeoJson(route.plan.stops, start) }
    // Everything drawn on top of the base map is declared here; MapLibre redraws it as the data changes.
    val mapState = rememberMapState(baseStyle = BaseStyle.Uri(MAP_STYLE)) {
        val lineSource = rememberGeoJsonSource(GeoJsonData.JsonString(lines))
        // Flights and ferries: thin dashed grey, not counted as driving.
        LineLayer(
            id = "jumps",
            source = lineSource,
            filter = feature["jump"].asBoolean(),
            color = const(Color(0xFF8A8A8A)),
            width = const(2.dp),
            dasharray = const(listOf(2, 2)),
        )
        LineLayer(
            id = "drives",
            source = lineSource,
            filter = !feature["jump"].asBoolean(),
            color = feature["color"].convertToColor(),
            width = const(4.dp),
        )
        // Stops close together on screen merge into one numbered circle until zoomed in.
        val stopSource = rememberGeoJsonSource(GeoJsonData.JsonString(stops)) {
            cluster = true
            clusterRadius = 40
        }
        CircleLayer(
            id = "stop-groups",
            source = stopSource,
            filter = feature.has("point_count"),
            color = const(Color(0xFF2B2B2B)),
            radius = const(16.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
        )
        SymbolLayer(
            id = "stop-group-counts",
            source = stopSource,
            filter = feature.has("point_count"),
            textField = format(span(feature["point_count_abbreviated"].asString())),
            textFont = const(listOf("Noto Sans Bold")), // a font the map style's server has
            textColor = const(Color.White),
            textSize = const(13.sp),
            textAllowOverlap = const(true),
        )
        CircleLayer(
            id = "stops",
            source = stopSource,
            filter = !feature.has("point_count"),
            color = feature["color"].convertToColor(),
            radius = const(9.dp),
            strokeColor = const(Color.White),
            strokeWidth = const(2.dp),
            onClick = { features ->
                val index = (features.firstOrNull()?.properties?.get("stop") as? JsonPrimitive)?.intOrNull
                if (index != null) onStop(index)
                ClickResult.Consume
            },
        )
    }
    LaunchedEffect(route) {
        val points = route.legs.flatMap { it.path } + route.plan.stops.map { it.at }
        // At least about a kilometre across, so a one-stop trip isn't shown at street level.
        val pad = 0.005
        val box = BoundingBox(
            west = points.minOf { it.lng } - pad, south = points.minOf { it.lat } - pad,
            east = points.maxOf { it.lng } + pad, north = points.maxOf { it.lat } + pad,
        )
        mapState.fitCameraToBounds(box, CameraFit(fitPadding = DpPadding(40.dp, 40.dp, 40.dp, 40.dp)))
    }
    MaplibreMap(modifier = modifier, state = mapState)
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

private fun duration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}

private fun linesGeoJson(route: TripRoute, start: LocalDate): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        for (leg in route.legs) addJsonObject {
            put("type", "Feature")
            putJsonObject("properties") {
                put("color", dayColor(dayNumber(start, leg.leg.day)))
                put("jump", !leg.leg.driven)
            }
            putJsonObject("geometry") {
                put("type", "LineString")
                // GeoJSON is longitude first.
                putJsonArray("coordinates") { for (p in leg.path) addJsonArray { add(p.lng); add(p.lat) } }
            }
        }
    }
}.toString()

private fun stopsGeoJson(stops: List<Stop>, start: LocalDate): String = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") {
        stops.forEachIndexed { index, stop ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("stop", index)
                    put("color", dayColor(dayNumber(start, stop.day)))
                }
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(stop.at.lng); add(stop.at.lat) }
                }
            }
        }
    }
}.toString()
