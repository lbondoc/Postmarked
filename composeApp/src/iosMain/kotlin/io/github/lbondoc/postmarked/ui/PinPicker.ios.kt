package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.domain.geo.LatLng

// shortcut: no tiled map here (maplibre-compose has no wasmJs build; iOS is compile-only), so places come from search alone.
internal actual val hasPinPicker = false

@Composable
internal actual fun PinPicker(at: LatLng, onMove: (LatLng) -> Unit, modifier: Modifier) = Unit
