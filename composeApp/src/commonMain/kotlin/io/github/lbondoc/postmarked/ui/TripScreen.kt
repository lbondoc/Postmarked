package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
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
import io.github.lbondoc.postmarked.data.Song
import io.github.lbondoc.postmarked.data.Songs
import io.github.lbondoc.postmarked.data.places
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.mergePalettes
import io.github.lbondoc.postmarked.domain.trip.TripArea
import io.github.lbondoc.postmarked.data.batchProgress
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.toRoutePoint
import io.github.lbondoc.postmarked.domain.route.planRoute
import io.github.lbondoc.postmarked.data.inviteLink

class TripViewModel(private val tripId: String) : ScreenModel() {
    var trip by mutableStateOf<Trip?>(null)
        private set
    var media by mutableStateOf<List<TripMedia>>(emptyList())
        private set
    var thumbnails by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var places by mutableStateOf<Map<String, TripArea>>(emptyMap())
        private set
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set

    // Kept here, not in the screen, so they survive rotating the phone.
    var view by mutableStateOf(GalleryView.All)
    var filter by mutableStateOf(GalleryFilter())

    /** Set while an invite is on screen. It cannot be fetched again later, only replaced. */
    var inviteToken by mutableStateOf<String?>(null)
        private set

    fun load() = run {
        trip = Trips.byId(tripId)
        songs = Songs.ofTrip(tripId)
    }

    fun loadSongs() = run { songs = Songs.ofTrip(tripId) }

    fun loadMedia() = run {
        val items = Media.ofTrip(tripId)
        thumbnails = Media.thumbnailUrls(tripId, items.map { it.id })
        if (places.isEmpty()) places = Trips.places(tripId)
        media = items
        // Photos uploaded before palettes existed get theirs now, once (it is saved for everyone).
        val missing = items.filter { it.palette == null }
        if (missing.isNotEmpty()) {
            val filled = Media.fillPalettes(tripId, missing)
            media = media.map { filled[it.id] ?: it }
        }
    }

    fun invite(role: TripRole) = run { inviteToken = Trips.createInvite(tripId, role) }

    fun setTheme(themeId: String, accent: String?) = run {
        Trips.setTheme(tripId, themeId, accent)
        trip = Trips.byId(tripId)
    }

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
    onOpenMap: () -> Unit,
    onOpenSummaries: () -> Unit,
    onShare: (String) -> Unit,
    videoPlayer: VideoPlayer,
    vm: TripViewModel = viewModel { TripViewModel(tripId) },
) {
    var choosingRole by remember { mutableStateOf(false) }
    /** The list the viewer swipes through, and where it opened. */
    var viewing by remember { mutableStateOf<Pair<List<TripMedia>, Int>?>(null) }
    var confirmingExit by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    val uploads by cameraRoll.uploads.collectAsState()
    val uploadProgress by cameraRoll.uploadProgress.collectAsState()
    val mobileData by cameraRoll.mobileData.collectAsState()
    val mine = uploads.filter { it.tripId == tripId }
    val uploaded = mine.count { it.finished }

    LaunchedEffect(Unit) { vm.load() }
    // Runs on opening and again each time one of this phone's uploads finishes, so new items appear.
    LaunchedEffect(uploaded) { vm.loadMedia() }
    val trip = vm.trip
    val isOwner = trip?.myRole == TripRole.Owner
    val look = rememberAlbumLook(trip, vm.media)
    // Names are looked up only once someone arranges by stop.
    val stopPoints = remember(vm.media, vm.view) {
        if (vm.view == GalleryView.Stops) planRoute(vm.media.mapNotNull { it.toRoutePoint() }).stops.map { it.at } else emptyList()
    }
    val stopNames = rememberStopNames(stopPoints)

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
                    look?.let { AlbumHeader(trip, it) } ?: Text(trip.dates, style = MaterialTheme.typography.titleMedium)
                    trip.regions.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                    if (trip.myRole?.canEdit == true) ThemePicker(trip, tripPalette(vm.media)) { id, accent -> vm.setTheme(id, accent) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenMap, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary),
                        ) { Text("Map and route") }
                        Button(onClick = onOpenSummaries, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) { Text("Summaries") }
                    }

                    SongsSection(trip, vm.songs, onChanged = vm::loadSongs)

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

                    batchProgress(mine, uploadProgress)?.let { progress ->
                        Text("Uploading ${progress.done} of ${progress.total} from this phone" + if (mobileData) "" else " (on Wi-Fi only)")
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.toggleable(mobileData, role = Role.Switch, onValueChange = cameraRoll::allowMobileData),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(checked = mobileData, onCheckedChange = null) // the whole row is the switch
                            Text("Use mobile data")
                        }
                    }
                    val failed = mine.mapNotNull { it.problem }
                    if (failed.isNotEmpty()) {
                        Text(
                            "${failed.size} couldn't be uploaded: " + failed.distinct().joinToString(" "),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (trip != null) gallery(tripId, trip.startDate, vm, stopNames, onFilter = { filtering = true }) { list, index -> viewing = list to index }
        }
    }

    if (filtering && trip != null) {
        FilterSheet(vm.media, vm.places, trip.startDate, vm.filter, onChange = { vm.filter = it }, onClose = { filtering = false })
    }

    viewing?.let { (list, index) ->
        Viewer(
            tripId = tripId,
            items = list,
            start = index,
            thumbnails = vm.thumbnails,
            videoPlayer = videoPlayer,
            onDeleted = {
                viewing = null
                vm.loadMedia()
            },
            onClose = { viewing = null },
        )
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

/** The trip's main colours, from every photo's palette. */
fun tripPalette(media: List<TripMedia>): List<Swatch> = mergePalettes(media.filter { !it.palette.isNullOrEmpty() }.map { it.swatches })

/** One square in a grid of a trip's photos and videos. */
@Composable
internal fun Thumbnail(tripId: String, id: String, kind: MediaKind, url: String?) {
    val path = Media.path(tripId, id, MediaKind.THUMB)
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(
            // Signed links change on every load; keying the cache by file path keeps thumbnails cached anyway.
            model = ImageRequest.Builder(LocalPlatformContext.current).data(url).memoryCacheKey(path).diskCacheKey(path).build(),
            contentDescription = if (kind == MediaKind.Video) "Video" else "Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (kind == MediaKind.Video) Text(
            "▶",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
        )
    }
}
