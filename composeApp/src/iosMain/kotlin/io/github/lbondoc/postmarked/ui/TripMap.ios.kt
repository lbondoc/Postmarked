package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.data.TripRoute
import kotlinx.datetime.LocalDate

// shortcut: stub, implement when a Mac is available. maplibre-compose has iOS builds, so iOS can get the real map.
@Composable
internal actual fun TripMap(route: TripRoute, start: LocalDate, onStop: (Int) -> Unit, modifier: Modifier) =
    RouteDrawing(route, start, onStop, modifier)
