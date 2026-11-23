package io.github.lbondoc.postmarked

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.ComposeUIViewController
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.PhotoSaver
import io.github.lbondoc.postmarked.data.SavedFile
import io.github.lbondoc.postmarked.data.QueuedUpload
import io.github.lbondoc.postmarked.data.ScanResult
import io.github.lbondoc.postmarked.ui.App
import io.github.lbondoc.postmarked.ui.ExportedImage
import io.github.lbondoc.postmarked.ui.Exporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The iOS app's screen, for an Xcode project to show. Nothing builds one yet: iOS only compiles in CI (SPEC §1.3). */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController() = ComposeUIViewController {
    App(
        onShare = {}, // shortcut: stub, implement when a Mac is available
        scanQr = { null }, // shortcut: stub, implement when a Mac is available
        cameraRoll = IosCameraRoll,
        videoPlayer = { _, modifier -> Box(modifier, Alignment.Center) { Text(NOT_YET) } },
        exporter = IosExporter,
        photoSaver = IosPhotoSaver,
    )
}

private const val NOT_YET = "Not available on iOS yet"

// shortcut: stub, implement when a Mac is available
private object IosCameraRoll : CameraRoll {
    override suspend fun requestAccess() = false // the import screen then says photo access is off
    override suspend fun scan(fromMs: Long, untilMs: Long) = ScanResult(emptyList(), locationAllowed = false)
    override val uploads: StateFlow<List<QueuedUpload>> = MutableStateFlow(emptyList())
    override val uploadProgress: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    override fun upload(tripId: String, items: List<DeviceMedia>) = Unit
    override val mobileData: StateFlow<Boolean> = MutableStateFlow(true)
    override fun allowMobileData(allowed: Boolean) = Unit
}

// shortcut: stub, implement when a Mac is available
private object IosExporter : Exporter {
    override fun share(images: List<ExportedImage>, text: String) = Unit
    override fun postToInstagram(image: ExportedImage, text: String) = Unit
    override fun openInstagram() = false
    override suspend fun save(images: List<ExportedImage>) = throw UnsupportedOperationException(NOT_YET)
    override fun copy(text: String) = Unit
}

// shortcut: stub, implement when a Mac is available
private object IosPhotoSaver : PhotoSaver {
    override suspend fun save(file: SavedFile) = throw UnsupportedOperationException(NOT_YET)
    override suspend fun share(files: List<SavedFile>) = throw UnsupportedOperationException(NOT_YET)
}
