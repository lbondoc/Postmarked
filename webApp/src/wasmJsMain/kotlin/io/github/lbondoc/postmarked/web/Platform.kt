package io.github.lbondoc.postmarked.web

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.QueuedUpload
import io.github.lbondoc.postmarked.data.ScanResult
import io.github.lbondoc.postmarked.ui.ExportedImage
import io.github.lbondoc.postmarked.ui.Exporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun jsShare(text: String): Unit = js(
    "navigator.share ? navigator.share({ text: text }).catch(() => {}) : navigator.clipboard.writeText(text)",
)

private fun jsOpen(url: String): Unit = js("window.open(url, '_blank', 'noopener')")

/** Web Share where the browser has it (phones), else the text goes to the clipboard. */
internal fun shareText(text: String) = jsShare(text)

/** Compose draws on a canvas, so a video opens in its own tab, where the browser plays it. */
@Composable
internal fun WebVideo(url: String, modifier: Modifier) = Box(modifier, Alignment.Center) {
    TextButton(onClick = { jsOpen(url) }) { Text("Play video") }
}

// shortcut: picking files to upload comes with task P5 (web upload), until then the web can only browse.
internal object WebCameraRoll : CameraRoll {
    override suspend fun requestAccess() = false
    override suspend fun scan(fromMs: Long, untilMs: Long) = ScanResult(emptyList(), locationAllowed = false)
    override val uploads: StateFlow<List<QueuedUpload>> = MutableStateFlow(emptyList())
    override val uploadProgress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    override fun upload(tripId: String, items: List<DeviceMedia>) = Unit
    override val mobileData: StateFlow<Boolean> = MutableStateFlow(true)
    override fun allowMobileData(allowed: Boolean) = Unit
}

// shortcut: downloads instead of the share sheet come with task P5, until then export copies the caption only.
internal object WebExporter : Exporter {
    override fun share(images: List<ExportedImage>, text: String) = shareText(text)
    override fun postToInstagram(image: ExportedImage, text: String) = shareText(text)
    override fun openInstagram() = false
    override suspend fun save(images: List<ExportedImage>) = Unit
    override fun copy(text: String) = shareText(text)
}
