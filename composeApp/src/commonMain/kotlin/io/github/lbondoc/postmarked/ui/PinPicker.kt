package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.domain.geo.LatLng

/** Whether this platform has a tiled map to fine-tune a place on (SPEC §9.3): Android only, see §1.1. */
internal expect val hasPinPicker: Boolean

/** A map starting at [at] under a fixed pin: dragging the map moves the spot, reported through [onMove]. */
@Composable
internal expect fun PinPicker(at: LatLng, onMove: (LatLng) -> Unit, modifier: Modifier)
