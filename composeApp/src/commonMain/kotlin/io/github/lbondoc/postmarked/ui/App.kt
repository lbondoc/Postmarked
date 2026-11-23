package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.supabase
import io.github.lbondoc.postmarked.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.serialization.Serializable

// Each screen is a "route". Navigation keeps a back stack of these; a data class route
// carries its arguments (which trip to show) in a type-safe way.
@Serializable private object TripListRoute
@Serializable private object NewTripRoute
@Serializable private data class TripRoute(val id: String)
@Serializable private data class ImportRoute(val tripId: String)
@Serializable private data class MapRoute(val tripId: String)

/**
 * The whole app. The parameters are things only the platform can do, handed in by the
 * Android Activity (and later by the iOS app): open the share sheet, scan a QR code, and
 * read and upload the camera roll.
 */
@Composable
fun App(onShare: (String) -> Unit, scanQr: suspend () -> String?, cameraRoll: CameraRoll, lastCrash: String? = null) {
    MaterialTheme {
        var crash by remember { mutableStateOf(lastCrash) }
        crash?.let { report ->
            AlertDialog(
                onDismissRequest = { crash = null },
                title = { Text("Postmarked closed unexpectedly") },
                text = { Text("Sharing the error details helps get it fixed.") },
                confirmButton = { TextButton(onClick = { onShare(report); crash = null }) { Text("Share details") } },
                dismissButton = { TextButton(onClick = { crash = null }) { Text("Not now") } },
            )
        }
        // collectAsState turns a Flow into Compose state: the UI redraws on every new value.
        val session by supabase.auth.sessionStatus.collectAsState()
        when (session) {
            is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is SessionStatus.Authenticated -> SignedIn(onShare, scanQr, cameraRoll)
            // shortcut: a failed token refresh while offline also lands here, keep the user in once offline mode exists.
            else -> SignInScreen()
        }
    }
}

@Composable
private fun SignedIn(onShare: (String) -> Unit, scanQr: suspend () -> String?, cameraRoll: CameraRoll) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = TripListRoute) {
        composable<TripListRoute> {
            TripListScreen(
                onOpen = { nav.navigate(TripRoute(it)) },
                onNew = { nav.navigate(NewTripRoute) },
                scanQr = scanQr,
            )
        }
        composable<NewTripRoute> {
            NewTripScreen(
                onBack = { nav.popBackStack() },
                // Replace the form with the new trip, so Back goes to the list, not the form.
                onCreated = { nav.navigate(TripRoute(it)) { popUpTo<TripListRoute>() } },
            )
        }
        composable<TripRoute> { entry ->
            val tripId = entry.toRoute<TripRoute>().id
            TripScreen(
                tripId = tripId,
                cameraRoll = cameraRoll,
                onBack = { nav.popBackStack() },
                onAddMedia = { nav.navigate(ImportRoute(tripId)) },
                onOpenMap = { nav.navigate(MapRoute(tripId)) },
                onShare = onShare,
            )
        }
        composable<MapRoute> { entry ->
            MapScreen(tripId = entry.toRoute<MapRoute>().tripId, onBack = { nav.popBackStack() })
        }
        composable<ImportRoute> { entry ->
            ImportScreen(tripId = entry.toRoute<ImportRoute>().tripId, cameraRoll = cameraRoll, onDone = { nav.popBackStack() })
        }
    }
}

/**
 * Base for each screen's ViewModel. A ViewModel outlives screen rotation and holds the
 * screen's state; `mutableStateOf` makes that state observable so the UI redraws on change.
 */
abstract class ScreenModel : ViewModel() {
    var error by mutableStateOf<String?>(null)
        protected set
    var busy by mutableStateOf(false)
        protected set

    /** Runs a backend call off the main thread's back, showing a message if it fails. */
    protected fun run(block: suspend () -> Unit) {
        // viewModelScope cancels the work automatically when the screen is gone for good.
        viewModelScope.launch {
            error = null
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e // cancellation is how coroutines stop; never swallow it
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
}

// shortcut: English month names only, switch to the platform's locale formatting when translating the app.
internal val DAY = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(Padding.NONE); char(','); char(' '); year()
}

fun dateRange(start: LocalDate, end: LocalDate) = "${start.format(DAY)} – ${end.format(DAY)}"

val Trip.dates get() = dateRange(startDate, endDate)
