package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.data.TripRoute
import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlinx.datetime.LocalDate

/** The route on a map: MapLibre tiles where the platform has them (Android), else [RouteDrawing]. */
@Composable
internal expect fun TripMap(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier)

/**
 * The route drawn on paper with no map tiles: day-coloured roads, dashed flights and ferries, tappable stops.
 * Used where maplibre-compose has no build (web), and later for the carousel's route slide.
 */
@Composable
internal fun RouteDrawing(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier) {
    val points = remember(route) { route.legs.flatMap { it.path } + route.plan.stops.map { it.at } }
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier.background(paper).pointerInput(route) {
            detectTapGestures { tap ->
                val at = routeProjection(points, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx())
                val reach = 24.dp.toPx()
                route.plan.stops.indices
                    .minByOrNull { (at(route.plan.stops[it].at) - tap).getDistance() }
                    ?.takeIf { (at(route.plan.stops[it].at) - tap).getDistance() <= reach }
                    ?.let(onStop)
            }
        },
    ) {
        val at = routeProjection(points, size.width, size.height, 40.dp.toPx())
        for (leg in route.legs) {
            if (leg.path.size < 2) continue
            val path = Path().apply {
                moveTo(at(leg.path[0]).x, at(leg.path[0]).y)
                for (p in leg.path.drop(1)) lineTo(at(p).x, at(p).y)
            }
            val dash = 4.dp.toPx()
            drawPath(
                path,
                color = if (leg.leg.driven) parseHex(dayColor(dayNumber(start, leg.leg.day))) else Color(0xFF8A8A8A),
                style = if (leg.leg.driven) {
                    Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                } else {
                    Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
                },
            )
        }
        for (stop in route.plan.stops) {
            drawCircle(ink, radius = 11.dp.toPx(), center = at(stop.at))
            drawCircle(parseHex(dayColor(dayNumber(start, stop.day))), radius = 9.dp.toPx(), center = at(stop.at))
        }
    }
}

/**
 * Fits [points] into a [width] × [height] box with [pad] on every side, centred, keeping Web Mercator's shape
 * so the drawing looks like the same trip on a map. North is up. Spans under about a kilometre are widened.
 */
internal fun routeProjection(points: List<LatLng>, width: Float, height: Float, pad: Float): (LatLng) -> Offset {
    fun x(p: LatLng) = p.lng
    fun y(p: LatLng) = ln(tan(PI / 4 + p.lat * PI / 360)) * 180 / PI
    val minX = points.minOf(::x)
    val maxX = points.maxOf(::x)
    val minY = points.minOf(::y)
    val maxY = points.maxOf(::y)
    val scale = min(max(width - 2 * pad, 1f) / max(maxX - minX, 0.01), max(height - 2 * pad, 1f) / max(maxY - minY, 0.01))
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    return { p -> Offset((width / 2 + (x(p) - cx) * scale).toFloat(), (height / 2 - (y(p) - cy) * scale).toFloat()) }
}
