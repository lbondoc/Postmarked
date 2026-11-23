package io.github.lbondoc.postmarked.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import coil3.memory.MemoryCache
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

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Page(tripId, items[page], active = page == pager.currentPage, thumbnails[items[page].id], videoPlayer)
            }
            Row(Modifier.fillMaxWidth().background(SCRIM).systemBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("${pager.currentPage + 1} of ${items.size}", color = Color.White)
                Spacer(Modifier.weight(1f))
                if (item.contributor == myUserId) TextButton(onClick = { confirmingDelete = true }) { Text("Delete", color = Color.White) }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(SCRIM).systemBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${item.profile?.displayName ?: "Someone"} · ${item.localTime.format(MOMENT)}", color = Color.White)
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
                TextButton(onClick = { showComments = true }) { Text("Comments (${comments.size})", color = Color.White) }
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

@Composable
private fun Page(tripId: String, item: TripMedia, active: Boolean, thumbUrl: String?, videoPlayer: VideoPlayer) {
    // The bucket is private, so each page asks for a short-lived link to its file.
    val url by produceState<String?>(null, item.id) {
        value = try {
            Media.viewUrl(tripId, item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // the thumbnail stays on screen
        }
    }
    val thumbPath = Media.path(tripId, item.id, MediaKind.THUMB)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val link = url
        if (item.kind == MediaKind.Video && active && link != null) {
            videoPlayer(link, Modifier.fillMaxSize()) // only the page on screen plays
        } else {
            // Photos show the full picture once its link arrives; videos show their thumbnail until they play.
            val full = item.kind == MediaKind.Photo && link != null
            val key = if (full) Media.path(tripId, item.id, MediaKind.DISPLAY) else thumbPath
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(if (full) link else thumbUrl)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    // Shows the grid's thumbnail, already in memory, while the full picture loads.
                    .placeholderMemoryCacheKey(MemoryCache.Key(thumbPath))
                    .build(),
                contentDescription = if (item.kind == MediaKind.Video) "Video" else "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
