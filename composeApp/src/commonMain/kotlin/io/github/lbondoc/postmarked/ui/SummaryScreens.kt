package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.Routes
import io.github.lbondoc.postmarked.data.Summaries
import io.github.lbondoc.postmarked.data.Summary
import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.TripRole
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.domain.color.NEUTRAL_CHROMA
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.colourName
import io.github.lbondoc.postmarked.domain.color.harmonize
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.color.rgbToHex
import io.github.lbondoc.postmarked.domain.summary.Layout
import kotlin.math.roundToInt

private const val METERS_PER_MILE = 1609.344

/** Everything a summary is drawn from, loaded once per screen. */
class SummaryMaterial(
    val trip: Trip,
    val media: List<TripMedia>,
    val pictures: Map<String, String>,
    val thumbnails: Map<String, String>,
) {
    val byId = media.associateBy { it.id }
    val palette: List<Swatch> = tripPalette(media)
    val candidates = covers(media.filter { !it.palette.isNullOrEmpty() })

    fun gradeFor(summary: Summary): (SummaryTile) -> FloatArray? {
        val target = summary.colours.map { Swatch(Oklab.fromRgb(hexToRgb(it)), 1.0 / summary.colours.size) }
        return { tile ->
            val photo = tile.media?.let { byId[it] }
            if (summary.harmonize <= 0 || tile.noGrade || photo == null || target.isEmpty()) null
            else harmonize(photo.swatches, target, summary.harmonize).toColorMatrix()
        }
    }

    fun textColours(summary: Summary) = summary.colours.map(::hexToRgb) + palette.map { it.rgb }
}

private suspend fun loadMaterial(tripId: String): SummaryMaterial {
    val trip = Trips.byId(tripId)
    val all = Media.ofTrip(tripId)
    val filled = Media.fillPalettes(tripId, all.filter { it.palette == null })
    val media = all.map { filled[it.id] ?: it }
    return SummaryMaterial(trip, media, Media.pictureUrls(tripId, media), Media.thumbnailUrls(tripId, media.map { it.id }))
}

// ---------- The list of a trip's summaries ----------

class SummariesViewModel(private val tripId: String) : ScreenModel() {
    var material by mutableStateOf<SummaryMaterial?>(null)
        private set
    var summaries by mutableStateOf<List<Summary>?>(null)
        private set

    fun load() = run {
        material = loadMaterial(tripId)
        summaries = Summaries.ofTrip(tripId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummariesScreen(
    tripId: String,
    onBack: () -> Unit,
    onOpen: (summaryId: String?) -> Unit,
    vm: SummariesViewModel = viewModel { SummariesViewModel(tripId) },
) {
    LaunchedEffect(Unit) { vm.load() } // again on return from the editor, so saves show up
    val material = vm.material
    val summaries = vm.summaries
    Scaffold(topBar = { TopAppBar(title = { Text("Summaries") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (material == null || summaries == null) {
                    if (vm.error == null) CircularProgressIndicator()
                    return@item
                }
                if (material.trip.myRole?.canEdit == true) Button(onClick = { onOpen(null) }, enabled = material.candidates.isNotEmpty()) {
                    Text("New summary")
                }
                if (material.candidates.isEmpty()) Text("Add some photos to the trip first; summaries are built from their colours.")
                else if (summaries.isEmpty()) Text("No summaries yet.")
            }
            if (material != null) items(summaries.orEmpty(), key = { it.id!! }) { summary ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(summary.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    val layout = Layout.entries.firstOrNull { it.name == summary.layout } ?: Layout.Grid3x3
                    SummaryCanvas(
                        layout, summary.tiles, material.byId, material.pictures, material.gradeFor(summary),
                        material.textColours(summary), Modifier.fillMaxWidth(0.6f),
                    )
                }
            }
        }
    }
}

// ---------- Making and changing one summary ----------

class SummaryEditorViewModel(private val tripId: String, private val summaryId: String?) : ScreenModel() {
    var material by mutableStateOf<SummaryMaterial?>(null)
        private set
    var summary by mutableStateOf<Summary?>(null)
        private set
    var miles by mutableStateOf<Int?>(null)
        private set

    val layout get() = Layout.entries.firstOrNull { it.name == summary?.layout } ?: Layout.Grid3x3
    val mode get() = SummaryMode.entries.firstOrNull { it.name == summary?.mode } ?: SummaryMode.Manual

    fun load() = run {
        val m = loadMaterial(tripId)
        material = m
        summary = summaryId?.let { Summaries.get(it) } ?: build(m, SummaryMode.Monochrome, Layout.Grid3x3, focus = null)
        // Miles need the road route, which can be slow or unavailable; the summary works without them.
        miles = try {
            (Routes.build(tripId).days.sumOf { it.meters } / METERS_PER_MILE).roundToInt()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun build(m: SummaryMaterial, mode: SummaryMode, layout: Layout, focus: Oklab?, keep: Summary? = summary): Summary {
        val colours = when (mode) {
            SummaryMode.Monochrome -> listOfNotNull(focus ?: m.palette.firstOrNull { it.color.chroma >= NEUTRAL_CHROMA }?.color ?: m.palette.firstOrNull()?.color)
            SummaryMode.Palette -> m.palette.take(4).map { it.color }
            else -> m.palette.map { it.color }
        }
        val tiles = when {
            mode == SummaryMode.Manual -> fillManually(layout, keep?.tiles.orEmpty(), keep?.tiles.orEmpty().mapNotNull { it.media })
            colours.isEmpty() -> List(layout.slots.size) { SummaryTile() }
            else -> generateTiles(mode, layout, m.candidates, colours, m.trip.name)
        }
        val name = keep?.name?.takeIf { keep.id != null } ?: when (mode) {
            SummaryMode.Monochrome -> "${colours.firstOrNull()?.let(::colourName) ?: "One colour"} summary"
            SummaryMode.Palette -> "Trip palette"
            SummaryMode.Rainbow -> "Rainbow"
            SummaryMode.Manual -> "My summary"
        }
        return Summary(
            id = keep?.id, tripId = tripId, createdBy = keep?.createdBy, name = name, layout = layout.name, mode = mode.name,
            tiles = tiles, harmonize = keep?.harmonize ?: 0.0, colours = colours.map { rgbToHex(it.toRgb()) },
        )
    }

    private fun change(transform: (Summary) -> Summary) {
        summary = summary?.let(transform)
    }

    fun setMode(mode: SummaryMode) {
        val m = material ?: return
        summary = build(m, mode, layout, focus = null)
    }

    fun setLayout(layout: Layout) {
        val m = material ?: return
        summary = build(m, mode, layout, focus = summary?.colours?.firstOrNull()?.let { Oklab.fromRgb(hexToRgb(it)) }.takeIf { mode == SummaryMode.Monochrome })
    }

    fun setFocus(colour: Oklab) {
        val m = material ?: return
        summary = build(m, SummaryMode.Monochrome, layout, focus = colour)
    }

    fun setHarmonize(value: Double) = change { it.copy(harmonize = value) }
    fun rename(name: String) = change { it.copy(name = name.take(80)) }
    fun swap(a: Int, b: Int) = change { it.copy(tiles = swapTiles(it.tiles, a, b), mode = SummaryMode.Manual.name) }
    fun setTile(index: Int, tile: SummaryTile) = change { s ->
        s.copy(tiles = s.tiles.toMutableList().also { list -> while (list.size <= index) list += SummaryTile(); list[index] = tile }, mode = SummaryMode.Manual.name)
    }
    fun pick(ids: List<String>) = change { it.copy(tiles = fillManually(layout, it.tiles, ids), mode = SummaryMode.Manual.name) }

    fun save(onSaved: () -> Unit) = run {
        summary = Summaries.save(summary ?: return@run)
        onSaved()
    }

    fun delete(onDone: () -> Unit) = run {
        summary?.id?.let { Summaries.delete(it) }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryEditorScreen(
    tripId: String,
    summaryId: String?,
    onBack: () -> Unit,
    vm: SummaryEditorViewModel = viewModel(key = "summary-$summaryId") { SummaryEditorViewModel(tripId, summaryId) },
) {
    LaunchedEffect(Unit) { if (vm.summary == null) vm.load() }
    var editing by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    var pickMany by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val material = vm.material
    val summary = vm.summary
    val role = material?.trip?.myRole
    // Owners and editors make summaries; the maker or the trip owner can change one.
    val canEdit = role?.canEdit == true && (summary?.id == null || summary.createdBy == myUserId || role == TripRole.Owner)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(summary?.name ?: "Summary") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { if (canEdit) TextButton(onClick = { vm.save(onBack) }, enabled = !vm.busy && summary?.name?.isNotBlank() == true) { Text("Save") } },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (material == null || summary == null) {
                if (vm.error == null) CircularProgressIndicator()
                return@Column
            }
            SummaryCanvas(
                vm.layout, summary.tiles, material.byId, material.pictures, material.gradeFor(summary), material.textColours(summary),
                onSwap = if (canEdit) vm::swap else null,
                onTap = if (canEdit) { index -> editing = index } else null,
            )
            if (!canEdit) return@Column
            Text("Tap a tile to change it. Long-press and drag a tile onto another to swap them.", style = MaterialTheme.typography.bodySmall)

            ChoiceRow("Style", SummaryMode.entries.map { it to it.label }, vm.mode) { mode ->
                if (mode == SummaryMode.Manual) { pickMany = true; picking = vm::pick } else vm.setMode(mode)
            }
            if (vm.mode == SummaryMode.Monochrome) {
                SwatchRow("Colour", material.palette.map { it.color }, summary.colours.firstOrNull()) { vm.setFocus(it) }
                val matching = material.candidates.count { io.github.lbondoc.postmarked.domain.color.share(it.swatches, Oklab.fromRgb(hexToRgb(summary.colours.first()))) >= MONOCHROME_SHARE }
                Text("$matching photos are mostly this colour. Empty places become colour tiles.", style = MaterialTheme.typography.bodySmall)
            }
            ChoiceRow("Layout", Layout.entries.map { it to it.label }, vm.layout, vm::setLayout)
            Text("Harmonize colours: ${(summary.harmonize * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = summary.harmonize.toFloat(), onValueChange = { vm.setHarmonize(it.toDouble()) })
            Text("Gives every photo the same look, from this summary's colours. Your photos themselves aren't changed.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pickMany = true; picking = vm::pick }) { Text("Choose the photos myself") }
            OutlinedTextField(value = summary.name, onValueChange = vm::rename, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (summary.id != null) TextButton(onClick = { confirmingDelete = true }) { Text("Delete summary", color = MaterialTheme.colorScheme.error) }
        }
    }

    val index = editing
    if (index != null && material != null && summary != null) {
        TileSheet(
            index = index,
            tile = summary.tiles.getOrElse(index) { SummaryTile() },
            slotCount = vm.layout.slots.size,
            colours = (summary.colours + material.palette.map { rgbToHex(it.rgb) } + listOf("#FFFFFF", "#1F1B16")).distinct(),
            quickTexts = listOfNotNull(
                material.trip.name, material.trip.dates, vm.miles?.let { "$it miles" },
            ) + material.trip.regions.map { it.label.substringBefore(',') },
            onChange = { vm.setTile(index, it) },
            onMove = { to -> vm.swap(index, to); editing = to },
            onPickPhoto = { pickMany = false; picking = { ids -> ids.firstOrNull()?.let { vm.setTile(index, SummaryTile(media = it)) } } },
            onClose = { editing = null },
        )
    }
    picking?.let { onPicked ->
        if (material != null) PhotoPicker(material, many = pickMany, onDone = { ids -> picking = null; if (ids.isNotEmpty()) onPicked(ids) })
    }
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("Delete this summary?") },
        text = { Text("The photos stay in the trip.") },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; vm.delete(onBack) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, chosen: T, onChoose: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, name) in options) FilterChip(selected = value == chosen, onClick = { onChoose(value) }, label = { Text(name) })
    }
}

@Composable
private fun SwatchRow(label: String, colours: List<Oklab>, chosenHex: String?, onChoose: (Oklab) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in colours) {
            val hex = rgbToHex(c.toRgb())
            FilterChip(
                selected = hex == chosenHex,
                onClick = { onChoose(c) },
                label = { Text(colourName(c)) },
                leadingIcon = { Box(Modifier.size(16.dp).background(rgbColor(c.toRgb()), CircleShape)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileSheet(
    index: Int,
    tile: SummaryTile,
    slotCount: Int,
    colours: List<String>,
    quickTexts: List<String>,
    onChange: (SummaryTile) -> Unit,
    onMove: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tile ${index + 1} of $slotCount", style = MaterialTheme.typography.titleMedium)
            if (tile.media != null) {
                Button(onClick = onPickPhoto) { Text("Replace photo") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = !tile.noGrade, onCheckedChange = { onChange(tile.copy(noGrade = !it)) })
                    Text("Harmonize this photo")
                }
                OutlinedButton(onClick = { onChange(SummaryTile(text = quickTexts.firstOrNull(), bg = colours.first())) }) { Text("Make it a text tile") }
            } else {
                OutlinedTextField(
                    value = tile.text.orEmpty(),
                    onValueChange = { onChange(tile.copy(text = it.take(80).ifEmpty { null }, bg = tile.bg ?: colours.first())) },
                    label = { Text("Text (leave empty for a plain colour)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (text in quickTexts) FilterChip(selected = tile.text == text, onClick = { onChange(tile.copy(text = text, bg = tile.bg ?: colours.first())) }, label = { Text(text) })
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (hex in colours) {
                        val rgb = hexToRgb(hex)
                        Box(
                            Modifier.size(36.dp)
                                .background(rgbColor(rgb), CircleShape)
                                .border(if (tile.bg == hex) 3.dp else 1.dp, if (tile.bg == hex) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable(onClickLabel = "Use ${colourName(Oklab.fromRgb(rgb))}") { onChange(tile.copy(bg = hex)) },
                        )
                    }
                }
                Button(onClick = onPickPhoto) { Text("Use a photo instead") }
            }
            // Buttons as well as dragging, so tiles can be moved without a long-press gesture.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMove(index - 1) }, enabled = index > 0) { Text("Move earlier") }
                OutlinedButton(onClick = { onMove(index + 1) }, enabled = index < slotCount - 1) { Text("Move later") }
            }
            TextButton(onClick = onClose) { Text("Done") }
        }
    }
}

/** Full-screen photo chooser. With [many], photos are numbered in the order tapped. */
@Composable
private fun PhotoPicker(material: SummaryMaterial, many: Boolean, onDone: (List<String>) -> Unit) {
    val chosen = remember { mutableStateListOf<String>() }
    Dialog(onDismissRequest = { onDone(emptyList()) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDone(emptyList()) }) { Text("Cancel") }
                    Text(if (many) "Choose photos in order" else "Choose a photo", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (many) TextButton(onClick = { onDone(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Use ${chosen.size}") }
                }
            },
        ) { insets ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.padding(insets),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(covers(material.media), key = { it.id }) { item ->
                    val position = chosen.indexOf(item.id)
                    Box(
                        Modifier.aspectRatio(1f).clickable(onClickLabel = if (position >= 0) "Remove" else "Choose") {
                            if (!many) onDone(listOf(item.id)) else if (position >= 0) chosen.remove(item.id) else chosen += item.id
                        },
                    ) {
                        Thumbnail(material.trip.id, item.id, item.kind, material.thumbnails[item.id])
                        if (position >= 0) Text(
                            "${position + 1}",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
