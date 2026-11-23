package io.github.lbondoc.postmarked.ui

import kotlinx.datetime.format
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import io.github.lbondoc.postmarked.data.myUserId
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import io.github.lbondoc.postmarked.domain.color.tripHues
import io.github.lbondoc.postmarked.domain.trip.TripArea
import io.github.lbondoc.postmarked.data.PhotoSaver
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
    /** Selected gallery tiles, by cover id. Empty when not selecting. */
    var selected by mutableStateOf(emptySet<String>())

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

    /** Owner and editors only: the photo shown for this trip on the trip list. */
    fun setCover(mediaId: String) = run {
        Trips.setCover(tripId, mediaId)
        trip = Trips.byId(tripId)
    }

    /** The items' own files to the share sheet (or Web Share). */
    fun share(saver: PhotoSaver, items: List<TripMedia>) = run { saver.share(items.map { Media.savedFile(tripId, it) }) }

    /** Deletes [mine] (the caller passes only the user's own items) and ends selecting. */
    fun deleteSelected(mine: List<TripMedia>) = run {
        for (item in mine) Media.delete(tripId, item)
        selected = emptySet()
        loadMedia()
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
    photoSaver: PhotoSaver,
    vm: TripViewModel = viewModel { TripViewModel(tripId) },
) {
    var choosingRole by remember { mutableStateOf(false) }
    /** The list the viewer swipes through, and where it opened. */
    var viewing by remember { mutableStateOf<Pair<List<TripMedia>, Int>?>(null) }
    var confirmingExit by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var confirmingDeleteSelected by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val saving = remember { SaveJob(scope) }
    val selectedItems = vm.media.filter { it.id in vm.selected }
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
    // Each item's stop for the viewer's info panel, numbered as in the Stops view.
    val stopTitles = remember(vm.media, stopNames) {
        planRoute(vm.media.mapNotNull { it.toRoutePoint() }).stops
            .flatMapIndexed { index, stop -> stop.ids.map { it to stopTitle(index, stopNames) } }.toMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.name ?: "Trip") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        bottomBar = {
            if (vm.selected.isNotEmpty() || saving.progress != null) SelectionBar(
                selectedItems, saving,
                onSave = { saving.start(tripId, selectedItems, photoSaver) },
                onShare = { vm.share(photoSaver, selectedItems) },
                onDelete = { confirmingDeleteSelected = true },
                onDone = { vm.selected = emptySet() },
            )
        },
    ) { insets ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(96.dp),
            // Press and hold a photo to start selecting, then drag across others to select the whole run.
            modifier = Modifier.fillMaxSize().padding(insets).dragSelect(
                gridState, scope, key = Unit,
                order = {
                    val start = vm.trip?.startDate ?: return@dragSelect emptyList()
                    gallerySections(filterMedia(vm.media, vm.filter, vm.places), vm.media, vm.view, start, stopNames)
                        .flatMap { section -> section.tiles.map { it.cover.id } }
                },
                selected = { vm.selected },
                onRange = { base, range, on -> vm.selected = selectRange(base, range, on) },
                selectHeld = true,
            ),
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
                    // The queue itself: each waiting or uploading item can be cancelled, or all of them.
                    val queued = mine.filter { it.waiting }
                    if (queued.isNotEmpty()) Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${queued.size} in the queue", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { cameraRoll.cancel(queued.map { it.mediaId }.toSet()) }) { Text("Cancel all") }
                        }
                        for (upload in queued) Row(verticalAlignment = Alignment.CenterVertically) {
                            val sent = uploadProgress[upload.mediaId]
                            Text(
                                (if (upload.item.kind == MediaKind.Video) "Video" else "Photo") + " · " + upload.item.takenOn.format(DAY) +
                                    (sent?.let { " · uploading ${(it * 100).toInt()}%" } ?: ""),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { cameraRoll.cancel(setOf(upload.mediaId)) }) { Text("Cancel") }
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

    if (confirmingDeleteSelected) {
        val (mine, skipped) = ownItems(selectedItems, myUserId)
        AlertDialog(
            onDismissRequest = { confirmingDeleteSelected = false },
            title = { Text(if (mine.isEmpty()) "None of these are yours" else "Delete ${mine.size} of yours?") },
            text = {
                Text(
                    listOfNotNull(
                        if (mine.isEmpty()) "You can only delete photos and videos you added." else "They're removed from the trip for everyone.",
                        skippedNote(skipped),
                    ).joinToString(" "),
                )
            },
            confirmButton = {
                if (mine.isNotEmpty()) TextButton(onClick = { confirmingDeleteSelected = false; vm.deleteSelected(mine) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDeleteSelected = false }) { Text("Cancel") } },
        )
    }

    viewing?.let { (list, index) ->
        Viewer(
            tripId = tripId,
            items = list,
            start = index,
            thumbnails = vm.thumbnails,
            videoPlayer = videoPlayer,
            photoSaver = photoSaver,
            tripStart = trip?.startDate,
            stopTitles = stopTitles,
            coverId = trip?.coverMediaId,
            onSetCover = if (trip?.myRole?.canEdit == true) { item -> vm.setCover(item.id) } else null,
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

/** The trip's main colours, from every photo's palette: its strongest hues, then at most one neutral. */
fun tripPalette(media: List<TripMedia>): List<Swatch> = tripHues(media.filter { !it.palette.isNullOrEmpty() }.map { it.swatches })

/** What can be done with the selected tiles, and how saving them is going. Delete only covers your own. */
@Composable
private fun SelectionBar(
    selected: List<TripMedia>,
    saving: SaveJob,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val skipped = ownItems(selected, myUserId).second
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            SavingBar(saving, MaterialTheme.colorScheme.onSurface)
            if (selected.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} selected", Modifier.weight(1f))
                    TextButton(onClick = onSave, enabled = saving.progress?.finished != false) { Text("Save") }
                    TextButton(onClick = onShare) { Text("Share") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                    TextButton(onClick = onDone) { Text("Done") }
                }
                skippedNote(skipped)?.let { Text("Delete: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

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
