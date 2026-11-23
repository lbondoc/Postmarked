package io.github.lbondoc.postmarked

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.lbondoc.postmarked.data.pendingInvite
import io.github.lbondoc.postmarked.ui.App
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // savedInstanceState is null only on a fresh start, so rotating the screen
        // does not redeem the same invite link a second time.
        if (savedInstanceState == null) readInvite(intent)
        val cameraRoll = AndroidCameraRoll(this) // before setContent: it registers for permission results
        val exporter = AndroidExporter(this)
        val photoSaver = AndroidPhotoSaver(this)
        // Shown once: the file is removed as soon as it is read.
        val lastCrash = crashReport(this).takeIf { it.exists() }?.let { file -> file.readText().also { file.delete() } }
        setContent {
            App(onShare = ::share, scanQr = ::scanQr, cameraRoll = cameraRoll, videoPlayer = { url, modifier -> VideoPlayer(url, modifier) }, exporter = exporter, photoSaver = photoSaver, lastCrash = lastCrash)
        }
    }

    /** Called instead of onCreate when an invite link is tapped while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    /** postmarked://join/<token>, as declared in the manifest. */
    private fun readInvite(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme == "postmarked" && link.host == "join") pendingInvite.value = link.lastPathSegment
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    /**
     * Opens Google's built-in scanner screen, which needs no camera permission of our own.
     * suspendCancellableCoroutine bridges its callbacks into a suspend function:
     * the caller simply waits for the text, or null if the scan was cancelled or failed.
     */
    private suspend fun scanQr(): String? = suspendCancellableCoroutine { continuation ->
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { continuation.resume(it.rawValue) }
            .addOnCanceledListener { continuation.resume(null) }
            .addOnFailureListener { continuation.resume(null) }
    }
}
