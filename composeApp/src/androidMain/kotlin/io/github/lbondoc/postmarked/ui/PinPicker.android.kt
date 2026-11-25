package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.domain.geo.LatLng
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private const val PIN_STYLE = "https://tiles.openfreemap.org/styles/positron" // same keyless style as the trip map

internal actual val hasPinPicker = true

@Composable
internal actual fun PinPicker(at: LatLng, onMove: (LatLng) -> Unit, modifier: Modifier) {
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(PIN_STYLE),
        initialCameraPosition = CameraPosition(center = Position(longitude = at.lng, latitude = at.lat), zoom = 15.0),
    )
    // The spot is whatever sits under the pin: the map's centre.
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition.center }.collect { onMove(LatLng(it.latitude, it.longitude)) }
    }
    Box(modifier.semantics { contentDescription = "Map: drag it to put the pin on the spot" }) {
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = mapState)
        // The pin's tip, not its middle, marks the spot.
        Text("📍", Modifier.align(Alignment.Center).offset(y = (-16).dp), fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
    }
}
