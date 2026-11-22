package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.TripRole
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.inviteLink

class TripViewModel(private val tripId: String) : ScreenModel() {
    var trip by mutableStateOf<Trip?>(null)
        private set
    var media by mutableStateOf<List<TripMedia>>(emptyList())
        private set
    var thumbnails by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Set while an invite is on screen. It cannot be fetched again later, only replaced. */
    var inviteToken by mutableStateOf<String?>(null)
        private set

    fun load() = run { trip = Trips.byId(tripId) }

    fun loadMedia() = run {
        val items = Media.ofTrip(tripId)
        thumbnails = Media.thumbnailUrls(tripId, items)
        media = items
    }

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
    cameraRoll: CameraRoll,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onShare: (String) -> Unit,
    vm: TripViewModel = viewModel { TripViewModel(tripId) },
) {
    var choosingRole by remember { mutableStateOf(false) }
    var confirmingExit by remember { mutableStateOf(false) }
    val uploads by cameraRoll.uploads.collectAsState()
    val mobileData by cameraRoll.mobileData.collectAsState()
    val mine = uploads.filter { it.tripId == tripId }
    val uploaded = mine.count { it.finished }

    LaunchedEffect(Unit) { vm.load() }
    // Runs on opening and again each time one of this phone's uploads finishes, so new items appear.
    LaunchedEffect(uploaded) { vm.loadMedia() }
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Everything above the photos is one full-width item.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Button(onClick = onAddMedia) { Text("Add photos and videos") }
                        Button(onClick = { choosingRole = true }, enabled = !vm.busy) { Text("Invite someone") }
                    }
                    OutlinedButton(onClick = { confirmingExit = true }, enabled = !vm.busy) {
                        Text(if (isOwner) "Delete trip" else "Leave trip")
                    }

                    val waiting = mine.count { it.waiting }
                    if (waiting > 0) {
                        Text("Uploading $waiting from this phone" + if (mobileData) "" else " when on Wi-Fi")
                    }
                    val failed = mine.mapNotNull { it.problem }
                    if (failed.isNotEmpty()) {
                        Text(
                            "${failed.size} couldn't be uploaded: " + failed.distinct().joinToString(" "),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Photos and videos (${vm.media.size})", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(vm.media, key = { it.id }) { item ->
                Thumbnail(item, tripId, vm.thumbnails[item.id])
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
                    if (isOwner) "This removes the trip and all its photos and videos for everyone on it. It cannot be undone."
                    else "You will need a new invite to come back. Photos you added stay in the trip."
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

@Composable
private fun Thumbnail(item: TripMedia, tripId: String, url: String?) {
    val path = Media.path(tripId, item.id, MediaKind.THUMB)
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(
            // Signed links change on every load; keying the cache by file path keeps thumbnails cached anyway.
            model = ImageRequest.Builder(LocalPlatformContext.current).data(url).memoryCacheKey(path).diskCacheKey(path).build(),
            contentDescription = if (item.kind == MediaKind.Video) "Video" else "Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == MediaKind.Video) Text(
            "▶",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
        )
    }
}
