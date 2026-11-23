package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.data.TripRoute
import kotlinx.datetime.LocalDate

// maplibre-compose has no wasmJs build (SPEC §1.1), so the web draws the route without tiles.
@Composable
internal actual fun TripMap(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier) =
    RouteDrawing(route, start, onStop, modifier)
