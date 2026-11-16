package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.TripRole
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.inviteLink

class TripViewModel(private val tripId: String) : ScreenModel() {
    var trip by mutableStateOf<Trip?>(null)
        private set

    /** Set while an invite is on screen. It cannot be fetched again later, only replaced. */
    var inviteToken by mutableStateOf<String?>(null)
        private set

    fun load() = run { trip = Trips.byId(tripId) }

    fun invite(role: TripRole) = run { inviteToken = Trips.createInvite(tripId, role) }

    fun closeInvite() {
        inviteToken = null
    }

    /** The owner deletes the trip for everyone; anyone else just leaves it. */
    fun leaveOrDelete(onDone: () -> Unit) = run {
        if (trip?.myRole == TripRole.Owner) Trips.delete(tripId) else Trips.leave(tripId)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(
    tripId: String,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    vm: TripViewModel = viewModel { TripViewModel(tripId) },
) {
    var choosingRole by remember { mutableStateOf(false) }
    var confirmingExit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    val trip = vm.trip
    val isOwner = trip?.myRole == TripRole.Owner

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.name ?: "Trip") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (trip == null) {
                if (vm.error == null) CircularProgressIndicator()
                return@Column
            }
            Text(trip.dates, style = MaterialTheme.typography.titleMedium)
            trip.regions.forEach { Text(it.label) }

            Text("People", style = MaterialTheme.typography.titleMedium)
            trip.members.sortedBy { it.role }.forEach { member ->
                ListItem(
                    headlineContent = { Text(member.profile.displayName) },
                    trailingContent = { Text(member.role.name) },
                )
            }

            if (trip.myRole?.canEdit == true) {
                Button(onClick = { choosingRole = true }, enabled = !vm.busy) { Text("Invite someone") }
            }
            OutlinedButton(onClick = { confirmingExit = true }, enabled = !vm.busy) {
                Text(if (isOwner) "Delete trip" else "Leave trip")
            }
        }
    }

    if (choosingRole) {
        AlertDialog(
            onDismissRequest = { choosingRole = false },
            title = { Text("Invite as") },
            text = { Text("Editors can add photos and change the trip. Viewers can only look, react and comment.") },
            confirmButton = {
                TextButton(onClick = { choosingRole = false; vm.invite(TripRole.Editor) }) { Text("Editor") }
            },
            dismissButton = {
                TextButton(onClick = { choosingRole = false; vm.invite(TripRole.Viewer) }) { Text("Viewer") }
            },
        )
    }

    vm.inviteToken?.let { token ->
        val link = inviteLink(token)
        AlertDialog(
            onDismissRequest = vm::closeInvite,
            title = { Text("Invite to ${trip?.name.orEmpty()}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // QR scanners need dark-on-white, so the code keeps a white card even in dark mode.
                    Image(
                        painter = rememberQrCodePainter(link),
                        contentDescription = "QR code for this invite",
                        modifier = Modifier.background(Color.White).padding(12.dp).size(220.dp),
                    )
                    Text("Have them scan this from Join a trip, or send them the link. It works for 14 days.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onShare("Join my trip \"${trip?.name.orEmpty()}\" on Postmarked: $link\n\nOr open Postmarked, tap Join a trip, and paste this code:\n$token")
                }) { Text("Share link") }
            },
            dismissButton = { TextButton(onClick = vm::closeInvite) { Text("Done") } },
        )
    }

    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text(if (isOwner) "Delete this trip?" else "Leave this trip?") },
            text = {
                Text(
                    if (isOwner) "This removes the trip for everyone on it and cannot be undone."
                    else "You will need a new invite to come back."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingExit = false; vm.leaveOrDelete(onBack) }) {
                    Text(if (isOwner) "Delete" else "Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingExit = false }) { Text("Cancel") } },
        )
    }
}
