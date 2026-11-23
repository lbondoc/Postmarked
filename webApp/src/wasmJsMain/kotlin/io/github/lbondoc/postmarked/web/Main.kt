package io.github.lbondoc.postmarked.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.lbondoc.postmarked.ui.App
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.lbondoc.postmarked.data.TripCache
import io.github.lbondoc.postmarked.data.supabase
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.browser.document

/** The website: the same App() as the phone, with the browser's versions of the platform pieces. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    TripCache.store = LocalStorageTripCache
    // Signing out forgets the saved trips: they belong to the account that loaded them.
    MainScope().launch {
        supabase.auth.sessionStatus.collect { if (it is SessionStatus.NotAuthenticated && it.isSignOut) TripCache.store.clear() }
    }
    // Mirrors the UI's semantics into the page, so screen readers (and the smoke test) can read it.
    ComposeViewport(document.body!!, configure = { isA11YEnabled = true }) {
        App(
            onShare = ::shareText,
            scanQr = { null }, // shortcut: no QR scanner on web, people join by opening the invite link
            cameraRoll = WebCameraRoll,
            videoPlayer = { url, modifier -> WebVideo(url, modifier) },
            exporter = WebExporter,
            photoSaver = WebPhotoSaver,
        )
    }
}
