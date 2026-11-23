package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.PhotoSaver
import io.github.lbondoc.postmarked.data.SaveProgress
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.saveEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Saving one or several items to the device, with progress and Cancel (SPEC §3.2). */
class SaveJob(private val scope: CoroutineScope) {
    var progress by mutableStateOf<SaveProgress?>(null)
        private set
    private var job: Job? = null

    fun start(tripId: String, items: List<TripMedia>, saver: PhotoSaver) {
        job?.cancel()
        job = scope.launch { saveEach(items, { saver.save(Media.savedFile(tripId, it)) }) { progress = it } }
    }

    /** Stops after the item being saved; those already saved stay. */
    fun cancel() {
        job?.cancel()
        progress = null
    }

    fun dismiss() {
        progress = null
    }
}

/** "Saving 2 of 5" with Cancel, then how it went. Nothing when no save is running or being reported. */
@Composable
fun SavingBar(saving: SaveJob, color: Color, modifier: Modifier = Modifier) {
    val p = saving.progress ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !p.finished -> "Saving ${p.done + 1} of ${p.total}"
                    p.failed == 0 -> "Saved ${p.total} to your device"
                    else -> "Saved ${p.total - p.failed} of ${p.total}; ${p.failed} couldn't be saved"
                },
                color = color, modifier = Modifier.weight(1f),
            )
            if (p.finished) TextButton(onClick = saving::dismiss) { Text("OK", color = color) }
            else TextButton(onClick = saving::cancel) { Text("Cancel", color = color) }
        }
        if (!p.finished) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
    }
}
