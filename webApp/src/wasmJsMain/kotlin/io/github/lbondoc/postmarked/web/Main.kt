package io.github.lbondoc.postmarked.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.lbondoc.postmarked.ui.App
import kotlinx.browser.document

/** The website: the same App() as the phone, with the browser's versions of the platform pieces. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Mirrors the UI's semantics into the page, so screen readers (and the smoke test) can read it.
    ComposeViewport(document.body!!, configure = { isA11YEnabled = true }) {
        App(
            onShare = ::shareText,
            scanQr = { null }, // shortcut: no QR scanner on web, people join by opening the invite link
            cameraRoll = WebCameraRoll,
            videoPlayer = { url, modifier -> WebVideo(url, modifier) },
            exporter = WebExporter,
        )
    }
}
