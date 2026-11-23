package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.data.TripRoute
import io.github.lbondoc.postmarked.domain.route.Stop
import kotlinx.datetime.LocalDate
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

@Composable
internal actual fun TripMap(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier) {
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
        try {
            mapState.fitCameraToBounds(box, CameraFit(fitPadding = DpPadding(40.dp, 40.dp, 40.dp, 40.dp)))
        } catch (_: IllegalStateException) {
            // The map closed (Back pressed) before it could move: nothing to do.
        }
    }
    MaplibreMap(modifier = modifier, state = mapState)
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
