package io.github.lbondoc.postmarked.ui

import kotlinx.datetime.LocalDate
import io.github.lbondoc.postmarked.data.placeName
import io.github.lbondoc.postmarked.data.PhotoSaver
import androidx.compose.runtime.produceState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.lbondoc.postmarked.data.Comment
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.REACTIONS
import io.github.lbondoc.postmarked.data.Reaction
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

/** Plays a video from a link. Only the platform can do it, so the Android app hands one in. */
typealias VideoPlayer = @Composable (url: String, modifier: Modifier) -> Unit

internal val MOMENT = LocalDateTime.Format {
    date(DAY); char(','); char(' '); amPmHour(Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
}

private val SCRIM = Color.Black.copy(alpha = 0.55f)

/** Full screen, swiping through [items] from [start]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(
    tripId: String,
    items: List<TripMedia>,
    start: Int,
    thumbnails: Map<String, String>,
    videoPlayer: VideoPlayer,
    photoSaver: PhotoSaver,
    /** For "Day N" in the info panel. */
    tripStart: LocalDate?,
    /** Each item's stop ("Stop 3 · Big Sur"), by media id. */
    stopTitles: Map<String, String>,
    onDeleted: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { items.size }
        val item = items[pager.currentPage]
        val scope = rememberCoroutineScope()
        // remember(item.id): these reset whenever the page changes.
        var reactions by remember(item.id) { mutableStateOf<List<Reaction>>(emptyList()) }
        var comments by remember(item.id) { mutableStateOf<List<Comment>>(emptyList()) }
        var problem by remember(item.id) { mutableStateOf<String?>(null) }
        var showComments by remember { mutableStateOf(false) }
        var showInfo by remember { mutableStateOf(false) }
        val saving = remember { SaveJob(scope) }
        var confirmingDelete by remember { mutableStateOf(false) }

        fun act(block: suspend () -> Unit) = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.userMessage()
            }
        }
        LaunchedEffect(item.id) {
            act {
                reactions = Media.reactions(item.id)
                comments = Media.comments(item.id)
            }
        }

        // Resets on every page change, so the next photo opens at 1×.
        var zoom by remember(pager.currentPage) { mutableStateOf(ZoomState()) }
        // Links to the full pictures, made for the photo on screen and the ones either side, which are then
        // downloaded ahead so swiping shows them at once. The bucket is private, so every file needs a signed link.
        var viewUrls by remember { mutableStateOf(emptyMap<String, String>()) }
        val context = LocalPlatformContext.current
        LaunchedEffect(pager.currentPage) {
            val near = (pager.currentPage - 1..pager.currentPage + 1).mapNotNull { items.getOrNull(it) }
            for (m in near.filter { it.id !in viewUrls }) {
                val url = try {
                    Media.viewUrl(tripId, m)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // the thumbnail stays on screen
                }
                viewUrls = viewUrls + (m.id to url)
                if (m != item && m.kind == MediaKind.Photo) SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // While zoomed, horizontal drags pan the photo instead of changing photos.
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoom.zoomed) { page ->
                val active = page == pager.currentPage
                Page(
                    items[page], active, thumbnails[items[page].id], viewUrls[items[page].id], videoPlayer,
                    zoom = if (active) zoom else ZoomState(), onZoom = { if (active) zoom = it },
                )
            }
            Row(Modifier.fillMaxWidth().background(SCRIM).systemBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("${pager.currentPage + 1} of ${items.size}", color = Color.White)
                Spacer(Modifier.weight(1f))
                // The photo's own file (the original, or the video): through the share sheet, or saved to the device.
                TextButton(onClick = { act { photoSaver.share(Media.savedFile(tripId, item)) } }) { Text("Share", color = Color.White) }
                TextButton(onClick = { saving.start(tripId, listOf(item), photoSaver) }, enabled = saving.progress?.finished != false) {
                    Text("Save", color = Color.White)
                }
                if (item.contributor == myUserId) TextButton(onClick = { confirmingDelete = true }) { Text("Delete", color = Color.White) }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(SCRIM).systemBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showInfo) Info(tripId, item, tripStart, stopTitles[item.id]) else {
                    Text("${item.profile?.displayName ?: "Someone"} · ${item.localTime.format(MOMENT)}", color = Color.White)
                }
                SavingBar(saving, Color.White)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (emoji in REACTIONS) {
                        val count = reactions.count { it.emoji == emoji }
                        val mine = reactions.any { it.emoji == emoji && it.userId == myUserId }
                        FilterChip(
                            selected = mine,
                            onClick = {
                                act {
                                    Media.react(item.id, emoji, on = !mine)
                                    reactions = Media.reactions(item.id)
                                }
                            },
                            label = { Text(if (count > 0) "$emoji $count" else emoji) },
                            // Light on the dark overlay; your own reactions in a white pill.
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Color.White,
                                selectedLabelColor = Color.Black,
                                selectedContainerColor = Color.White,
                            ),
                        )
                    }
                }
                Row {
                    TextButton(onClick = { showComments = true }) { Text("Comments (${comments.size})", color = Color.White) }
                    TextButton(onClick = { showInfo = !showInfo }) { Text(if (showInfo) "Hide info" else "ⓘ Info", color = Color.White) }
                }
            }
        }

        if (showComments) {
            ModalBottomSheet(onDismissRequest = { showComments = false }) {
                var draft by remember { mutableStateOf("") }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (comments.isEmpty()) Text("No comments yet.")
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(comments, key = { it.id }) { comment ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(comment.profile?.displayName ?: "Someone", style = MaterialTheme.typography.labelLarge)
                                    Text(comment.body)
                                }
                                if (comment.author == myUserId) TextButton(onClick = {
                                    act {
                                        Media.deleteComment(comment.id)
                                        comments = Media.comments(item.id)
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it.take(1000) },
                        label = { Text("Add a comment") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val body = draft
                            draft = ""
                            act {
                                Media.comment(item.id, body)
                                comments = Media.comments(item.id)
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Post") }
                }
            }
        }

        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete this ${if (item.kind == MediaKind.Video) "video" else "photo"}?") },
                text = { Text("It's removed from the trip for everyone, with its reactions and comments. The copy on your phone stays.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        act {
                            Media.delete(tripId, item)
                            onDeleted()
                        }
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

/** Who, when, which day and stop, where, and how big: the lines from [infoLines], filling in as lookups finish. */
@Composable
private fun Info(tripId: String, item: TripMedia, tripStart: LocalDate?, stop: String?) {
    val place by produceState<String?>(null, item.id) { value = item.location?.let { placeName(it) } }
    val bytes by produceState<Long?>(null, item.id) {
        value = try {
            Media.fileSize(tripId, item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // left out of the panel
        }
    }
    Column {
        for (line in infoLines(item, tripStart, stop, place, bytes = bytes)) Text(line, color = Color.White)
    }
}

@Composable
private fun Page(
    item: TripMedia,
    active: Boolean,
    thumbUrl: String?,
    viewUrl: String?,
    videoPlayer: VideoPlayer,
    zoom: ZoomState,
    onZoom: (ZoomState) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (item.kind == MediaKind.Video && active && viewUrl != null) {
            videoPlayer(viewUrl, Modifier.fillMaxSize()) // only the page on screen plays
        } else {
            // The thumbnail at once (usually already cached by the grid), the display picture over it when loaded.
            // Both are cached by Storage path (StoragePathKeys), so expired links still hit the cache.
            val layers = Modifier.fillMaxSize().then(if (item.kind == MediaKind.Photo) Modifier.zoomable(zoom, onZoom) else Modifier)
            Box(layers) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.kind == MediaKind.Photo && viewUrl != null) AsyncImage(
                    model = viewUrl,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Pinch and double-tap zoom for the viewer's photo, with the clamping in [ZoomState]. One-finger drags are
 * taken only while zoomed; at 1× they are left for the pager, so swiping still changes photos.
 */
@Composable
private fun Modifier.zoomable(zoom: ZoomState, onZoom: (ZoomState) -> Unit): Modifier {
    val latest by rememberUpdatedState(zoom)
    val update by rememberUpdatedState(onZoom)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { at -> update(latest.doubleTapped(at - size.toSize().center, size.toSize())) })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var state = latest // several events can arrive before the next recomposition
                do {
                    val event = awaitPointerEvent()
                    val page = size.toSize()
                    val next = if (event.changes.count { it.pressed } > 1) {
                        state.transformed(event.calculateZoom(), event.calculatePan(), event.calculateCentroid() - page.center, page)
                    } else {
                        state.swipe(event.calculatePan(), page)
                    }
                    if (next != null) {
                        state = next
                        update(next)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        // Inside the gesture handlers, so they see the screen's coordinates rather than the zoomed picture's.
        .graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.pan.x
            translationY = zoom.pan.y
        }
}
