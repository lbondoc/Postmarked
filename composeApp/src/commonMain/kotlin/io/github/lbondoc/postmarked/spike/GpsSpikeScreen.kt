package io.github.lbondoc.postmarked.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.domain.geo.LatLng

/** One camera-roll item as the spike sees it. [problem] is set when reading its metadata failed. */
data class SpikeItem(
    val name: String,
    val isVideo: Boolean,
    val takenAt: String,
    val gps: LatLng?,
    val problem: String? = null,
)

/**
 * Phase 0 throwaway screen: proves we can read GPS from the camera roll on a real phone.
 * It only draws state it is handed, so it stays platform-free; the Activity does the reading.
 *
 * @param items null until the first scan finishes.
 * @param access plain-language lines about the device and which permissions were granted.
 */
@Composable
fun GpsSpikeScreen(
    items: List<SpikeItem>?,
    scanning: Boolean,
    access: List<String>,
    onScan: () -> Unit,
) {
    MaterialTheme {
        Scaffold { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Postmarked: GPS import check", style = MaterialTheme.typography.headlineSmall)
                        access.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = onScan, enabled = !scanning) { Text("Scan camera roll") }
                        if (scanning) CircularProgressIndicator()
                        if (items != null) Text(summary(items), style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(items.orEmpty()) { item ->
                    HorizontalDivider()
                    Text("${if (item.isVideo) "Video" else "Photo"}  ${item.name}")
                    Text(item.takenAt, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = item.gps?.let { "GPS ${it.lat}, ${it.lng}" } ?: "No GPS${item.problem?.let { " ($it)" } ?: ""}",
                        color = if (item.gps != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun summary(items: List<SpikeItem>): String {
    // partition splits one list into (matching, not matching) in a single pass.
    val (videos, photos) = items.partition { it.isVideo }
    fun line(label: String, list: List<SpikeItem>) = "$label: GPS on ${list.count { it.gps != null }} of ${list.size}"
    return "${line("Photos", photos)}\n${line("Videos", videos)}"
}
