package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.TripCache
import io.github.lbondoc.postmarked.data.loadOrCached
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jan.supabase.auth.auth
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.pendingInvite
import io.github.lbondoc.postmarked.data.supabase
import io.github.lbondoc.postmarked.data.tokenFromInvite
import kotlinx.coroutines.launch

class TripListViewModel : ScreenModel() {
    /** null until the first load finishes. */
    var trips by mutableStateOf<List<Trip>?>(null)
        private set
    /** Cover thumbnails by trip id, for trips that have one. */
    var covers by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** True when the network failed and the list is the one saved last time. */
    var offline by mutableStateOf(false)
        private set

    fun refresh() = run {
        val cache = TripCache.shared
        val loaded = loadOrCached({ Trips.mine() }, cache::saveTrips, { cache.trips().ifEmpty { null } })
        trips = loaded.value
        offline = loaded.offline
        covers = if (offline) cache.covers() else Media.coverUrls(loaded.value).also(cache::saveCovers)
    }

    fun join(linkOrCode: String, onJoined: (String) -> Unit) = run { onJoined(Trips.join(tokenFromInvite(linkOrCode))) }

    fun signOut() = run { supabase.auth.signOut() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripListScreen(
    onOpen: (tripId: String) -> Unit,
    onNew: () -> Unit,
    scanQr: suspend () -> String?,
    vm: TripListViewModel = viewModel { TripListViewModel() },
) {
    var joining by remember { mutableStateOf(false) }
    val invite by pendingInvite.collectAsState()

    // LaunchedEffect runs its block when the screen appears, and again whenever its key changes.
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(invite) {
        invite?.let {
            pendingInvite.value = null
            vm.join(it, onOpen)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { PostmarkLogo(size = 40.dp); Text("  Trips") } },
                actions = { TextButton(onClick = vm::signOut) { Text("Sign out") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew) { Text("New trip") } },
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { joining = true }) { Text("Join a trip") }
                    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val trips = vm.trips
                    when {
                        trips == null && vm.error == null -> CircularProgressIndicator()
                        trips?.isEmpty() == true -> Text("No trips yet. Start one, or join a friend's with their invite.")
                    }
                }
            }
            if (vm.offline) item { OfflineBanner() }
            items(vm.trips.orEmpty(), key = { it.id }) { trip ->
                ListItem(
                    leadingContent = vm.covers[trip.id]?.let { url ->
                        {
                            AsyncImage(
                                model = url, contentDescription = "Trip cover", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                            )
                        }
                    },
                    headlineContent = { Text(trip.name) },
                    supportingContent = { Text(trip.dates + "\n" + trip.regions.joinToString(" · ") { it.label.substringBefore(',') }) },
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(trip.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (joining) {
        JoinDialog(
            scanQr = scanQr,
            onDismiss = { joining = false },
            onJoin = {
                joining = false
                vm.join(it, onOpen)
            },
        )
    }
}

@Composable
private fun JoinDialog(scanQr: suspend () -> String?, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a trip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Invite link or code") }, singleLine = true,
                )
                // A scanned code joins straight away; cancelling the scanner returns null and does nothing.
                OutlinedButton(onClick = { scope.launch { scanQr()?.let(onJoin) } }) { Text("Scan a QR code") }
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(code) }, enabled = code.isNotBlank()) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
