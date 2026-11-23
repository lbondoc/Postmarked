package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.lbondoc.postmarked.data.Song
import io.github.lbondoc.postmarked.data.SongPreview
import io.github.lbondoc.postmarked.data.Songs
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.TripRole
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.data.userMessage
import io.github.lbondoc.postmarked.domain.music.parseSongLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit

/** The trip's songs: open them in Spotify, add one by pasting a link, remove your own. */
@Composable
fun SongsSection(trip: Trip, songs: List<Song>, onChanged: () -> Unit) {
    val open = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Text("Music", style = MaterialTheme.typography.titleMedium)
    if (songs.isEmpty()) Text("No songs yet. Paste a Spotify link to give the trip a soundtrack.", style = MaterialTheme.typography.bodySmall)
    for (song in songs) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open in Spotify") { open.openUri(song.url) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(model = song.artworkUrl, contentDescription = null, modifier = Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(song.artist, song.day?.let { "Day ${trip.startDate.daysUntil(it) + 1}" }, "Open in Spotify").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (song.addedBy == myUserId || trip.myRole == TripRole.Owner) TextButton(onClick = {
                scope.launch {
                    try {
                        Songs.delete(song.id)
                        onChanged()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem = e.userMessage()
                    }
                }
            }) { Text("Remove") }
        }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (trip.myRole?.canEdit == true) TextButton(onClick = { adding = true }) { Text("Add a song") }
    if (adding) AddSongDialog(trip, onDone = { added -> adding = false; if (added) onChanged() })
}

@Composable
private fun AddSongDialog(trip: Trip, onDone: (added: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var preview by remember { mutableStateOf<SongPreview?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val link = parseSongLink(pasted)

    // Looks the song up as soon as a recognisable link is pasted.
    LaunchedEffect(link) {
        preview = null
        problem = null
        if (link == null) return@LaunchedEffect
        preview = try {
            Songs.preview(link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't look that link up: ${e.userMessage()}"
            null
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Add a song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it },
                    label = { Text("Spotify link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("In Spotify: Share → Copy link") },
                )
                if (pasted.isNotBlank() && link == null) Text("That isn't a Spotify song, album or playlist link.", color = MaterialTheme.colorScheme.error)
                if (link != null && preview == null && problem == null) CircularProgressIndicator()
                preview?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(model = p.artworkUrl, contentDescription = null, modifier = Modifier.size(56.dp))
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = artist, onValueChange = { artist = it.take(200) },
                        label = { Text("Artist (optional, for the caption)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Goes with", style = MaterialTheme.typography.labelLarge)
                    val days = trip.startDate.daysUntil(trip.endDate) + 1
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = day == null, onClick = { day = null }, label = { Text("Whole trip") })
                        for (n in 0 until minOf(days, 60)) {
                            val date = trip.startDate.plus(n, DateTimeUnit.DAY)
                            FilterChip(selected = day == date, onClick = { day = date }, label = { Text("Day ${n + 1}") })
                        }
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val l = link ?: return@Button
                    val p = preview ?: return@Button
                    saving = true
                    scope.launch {
                        try {
                            Songs.add(trip.id, l, p, artist, day)
                            onDone(true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = e.userMessage()
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = preview != null && !saving,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}
