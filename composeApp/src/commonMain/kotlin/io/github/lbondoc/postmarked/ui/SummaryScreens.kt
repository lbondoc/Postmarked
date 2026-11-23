package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.domain.color.COLOUR_WHEEL
import io.github.lbondoc.postmarked.domain.color.share
import io.github.lbondoc.postmarked.domain.color.then
import io.github.lbondoc.postmarked.domain.color.Look
import io.github.lbondoc.postmarked.domain.color.Adjustment
import io.github.lbondoc.postmarked.data.toData
import kotlinx.datetime.format
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
import io.github.lbondoc.postmarked.data.Song
import io.github.lbondoc.postmarked.data.Songs
import io.github.lbondoc.postmarked.data.Summaries
import io.github.lbondoc.postmarked.data.toRoutePoint
import io.github.lbondoc.postmarked.domain.route.planRoute
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

    /**
     * Each photo's colour matrix, or null to draw it untouched: Match colours first, then the
     * summary's filter and adjustments (unless the tile opted out), then the tile's own.
     */
    fun gradeFor(summary: Summary): (SummaryTile) -> FloatArray? {
        val target = summary.colours.map { Swatch(Oklab.fromRgb(hexToRgb(it)), 1.0 / summary.colours.size) }
        val shared = summary.look?.toLook()?.takeUnless { it.isPlain }?.grade()
        return { tile ->
            val photo = tile.media?.let { byId[it] }
            if (photo == null) null
            else listOfNotNull(
                harmonize(photo.swatches, target, summary.harmonize).takeIf { summary.harmonize > 0 && !tile.noGrade && target.isNotEmpty() },
                shared.takeUnless { tile.ownLook },
                tile.look?.toLook()?.takeUnless { it.isPlain }?.grade(),
            ).reduceOrNull { a, b -> a then b }?.toColorMatrix()
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
                val look = rememberAlbumLook(material.trip, material.media, forExport = true)
                Column(Modifier.fillMaxWidth().clickable { onOpen(summary.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    val layout = Layout.entries.firstOrNull { it.name == summary.layout } ?: Layout.Grid3x3
                    SummaryCanvas(
                        layout, summary.tiles, material.byId, material.pictures, material.gradeFor(summary),
                        material.textColours(summary), Modifier.fillMaxWidth(0.6f),
                        paper = look?.let { rgbColor(it.colours.paper) } ?: Color(0xFFF7F3EA),
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
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    /** True while someone makes their very first summary: they're walked through it a step at a time. */
    var guided by mutableStateOf(false)

    val layout get() = Layout.entries.firstOrNull { it.name == summary?.layout } ?: Layout.Grid3x3
    val mode get() = SummaryMode.entries.firstOrNull { it.name == summary?.mode } ?: SummaryMode.Manual

    fun load() = run {
        val m = loadMaterial(tripId)
        material = m
        summary = summaryId?.let { Summaries.get(it) } ?: build(m, SummaryMode.Monochrome, Layout.Grid3x3, focus = null)
        guided = summaryId == null && myUserId?.let { !Summaries.anyBy(it) } == true
        songs = Songs.ofTrip(tripId)
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
    fun setLook(look: Look) = change { it.copy(look = look.takeUnless { l -> l.isPlain }?.toData()) }
    fun rename(name: String) = change { it.copy(name = name.take(80)) }
    fun swap(a: Int, b: Int) = change { it.copy(tiles = swapTiles(it.tiles, a, b), mode = SummaryMode.Manual.name) }
    fun setTile(index: Int, tile: SummaryTile) = change { s ->
        s.copy(tiles = s.tiles.toMutableList().also { list -> while (list.size <= index) list += SummaryTile(); list[index] = tile }, mode = SummaryMode.Manual.name)
    }
    fun pick(ids: List<String>) = change { it.copy(tiles = fillManually(layout, it.tiles, ids), mode = SummaryMode.Manual.name) }

    /** Moves the [moving] tiles side by side from slot [target]; returns where they landed, so they stay selected. */
    fun move(moving: Set<Int>, target: Int): Set<Int> {
        val s = summary ?: return emptySet()
        val (tiles, landed) = moveTiles(List(layout.slots.size) { s.tiles.getOrNull(it) ?: SummaryTile() }, moving, target)
        summary = s.copy(tiles = tiles, mode = SummaryMode.Manual.name)
        return landed.toSet()
    }

    /** Gives each tile in [indices] its own [look], replacing any it had. */
    fun setLooks(indices: Collection<Int>, look: Look) = change { s ->
        s.copy(tiles = s.tiles.mapIndexed { i, t -> if (i in indices) t.copy(look = look.takeUnless { it.isPlain }?.toData()) else t }, mode = SummaryMode.Manual.name)
    }

    fun save(onSaved: () -> Unit) = run {
        summary = Summaries.save(summary ?: return@run)
        onSaved()
    }

    fun delete(onDone: () -> Unit) = run {
        summary?.id?.let { Summaries.delete(it) }
        onDone()
    }
}

/** The editor's bottom tabs; only the chosen one's controls are on screen. */
private enum class EditorTab(val label: String) { Style("Style"), Colour("Colour"), Layout("Layout"), Filter("Filter"), Adjust("Adjust") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryEditorScreen(
    tripId: String,
    summaryId: String?,
    exporter: Exporter,
    onBack: () -> Unit,
    vm: SummaryEditorViewModel = viewModel(key = "summary-$summaryId") { SummaryEditorViewModel(tripId, summaryId) },
) {
    LaunchedEffect(Unit) { if (vm.summary == null) vm.load() }
    var tab by remember { mutableStateOf(EditorTab.Style) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var photoEditing by remember { mutableStateOf<Pair<Int, PhotoTab>?>(null) }
    // Tiles chosen by holding them; Filter and Adjust then change only these.
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(vm.layout) { selected = emptySet() } // slot numbers mean something else in another layout
    var picking by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    var pickMany by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val material = vm.material
    val summary = vm.summary
    val role = material?.trip?.myRole
    // Owners and editors make summaries; the maker or the trip owner can change one.
    val canEdit = role?.canEdit == true && (summary?.id == null || summary.createdBy == myUserId || role == TripRole.Owner)
    val chooseMine = { pickMany = true; picking = vm::pick }

    if (vm.guided && material != null && summary != null) GuidedSummary(vm, material, summary, onClose = onBack, onChooseMine = chooseMine)
    else Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(if (canEdit) "Cancel" else "Back") }
                Text(
                    summary?.name ?: "Summary",
                    Modifier.weight(1f).then(if (canEdit && summary != null) Modifier.clickable(onClickLabel = "Rename") { renaming = true } else Modifier),
                    fontFamily = handFont(), fontWeight = FontWeight.SemiBold, fontSize = 26.sp, textAlign = TextAlign.Center, maxLines = 1,
                )
                // Anyone on the trip can export; only those who may change it can save.
                if (summary != null && material != null) TextButton(onClick = { exporting = true }) { Text("Export") }
                if (canEdit) InkButton("Done", onClick = { vm.save(onBack) }, enabled = !vm.busy && summary?.name?.isNotBlank() == true)
            }
        },
        bottomBar = {
            if (canEdit && summary != null) BottomTabs(EditorTab.entries.map { it to it.label }, tab, { tab = it }, Modifier.navigationBarsPadding())
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (material == null || summary == null) {
                if (vm.error == null) CircularProgressIndicator()
                return@Column
            }
            val look = rememberAlbumLook(material.trip, material.media, forExport = true)
            val paper = look?.let { rgbColor(it.colours.paper) } ?: Color(0xFFF7F3EA)
            // The summary sits on a sheet of paper, lifted off the page by a soft shadow.
            Box(Modifier.shadow(4.dp).background(paper).padding(8.dp)) {
                SummaryCanvas(
                    vm.layout, summary.tiles, material.byId, material.pictures, material.gradeFor(summary), material.textColours(summary),
                    paper = paper,
                    selected = selected,
                    onSelect = if (canEdit) { chosen -> selected = chosen } else null,
                    onMove = if (canEdit) { moving, target -> selected = vm.move(moving, target) } else null,
                    onTap = if (canEdit) { index -> editing = index } else null,
                )
            }
            if (!canEdit) return@Column
            SelectionBar(
                selected, vm.layout.slots.size,
                onEditPhoto = selected.singleOrNull()?.takeIf { summary.tiles.getOrNull(it)?.media != null }?.let { i -> { photoEditing = i to PhotoTab.Filter } },
                onChange = { selected = it },
            )
            PaperCard(Modifier.fillMaxWidth()) {
                when (tab) {
                    EditorTab.Style -> StylePanel(vm.mode, summary, material) { mode -> if (mode == SummaryMode.Manual) chooseMine() else vm.setMode(mode) }
                    EditorTab.Colour -> ColourPanel(vm.mode, summary, material, vm::setFocus)
                    EditorTab.Layout -> LayoutPanel(vm.layout, vm::setLayout)
                    EditorTab.Filter, EditorTab.Adjust -> if (selected.isNotEmpty()) {
                        val photos = selected.filter { summary.tiles.getOrNull(it)?.media != null }.sorted()
                        if (photos.isEmpty()) Text("The selected tiles have no photos to filter or adjust.", style = MaterialTheme.typography.bodyMedium)
                        else {
                            // Shows the first chosen photo's settings; a change applies to every chosen photo.
                            val first = summary.tiles[photos.first()]
                            val look = first.look?.toLook() ?: Look()
                            val scope = if (photos.size == 1) "1 selected photo" else "${photos.size} selected photos"
                            val setLook = { l: Look -> vm.setLooks(photos, l) }
                            if (tab == EditorTab.Filter) FilterStrip("Filter · $scope", look, material.thumbnails[first.media], { f ->
                                material.gradeFor(summary)(first.copy(look = look.copy(filter = f).toData()))
                            }, setLook)
                            else ToolPicker("Adjust · $scope", lookTools(look, Adjustment.entries - Adjustment.Tint, setLook)) { setLook(look.copy(adjust = emptyMap())) }
                            Text("These add to the whole summary's look. The other tiles stay as they are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (tab == EditorTab.Filter) {
                        WholeSummaryFilter(summary, material, vm::setLook)
                    } else {
                        val look = summary.look?.toLook() ?: Look()
                        val tools = listOf(Tool("Match", 0.0, summary.harmonize, vm::setHarmonize)) +
                            lookTools(look, Adjustment.entries - Adjustment.Tint, vm::setLook)
                        ToolPicker("Adjust · all photos", tools) { vm.setHarmonize(0.0); vm.setLook(look.copy(adjust = emptyMap())) }
                        Text(
                            "Match gives every photo the same colours, from this summary's palette. Your photos themselves are never changed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val index = editing
    if (index != null && material != null && summary != null) {
        TileSheet(
            index = index,
            tile = summary.tiles.getOrElse(index) { SummaryTile() },
            photo = summary.tiles.getOrNull(index)?.media?.let { material.byId[it] },
            thumbnail = summary.tiles.getOrNull(index)?.media?.let { material.thumbnails[it] },
            tripId = tripId,
            slotCount = vm.layout.slots.size,
            colours = (summary.colours + material.palette.map { rgbToHex(it.rgb) } + listOf("#FFFFFF", "#1F1B16")).distinct(),
            quickTexts = listOfNotNull(
                material.trip.name, material.trip.dates, vm.miles?.let { "$it miles" },
            ) + material.trip.regions.map { it.label.substringBefore(',') },
            onChange = { vm.setTile(index, it) },
            onMove = { to -> vm.swap(index, to); editing = to },
            onPickPhoto = { pickMany = false; picking = { ids -> ids.firstOrNull()?.let { vm.setTile(index, SummaryTile(media = it)) } } },
            onEditPhoto = { tab -> editing = null; photoEditing = index to tab },
            onClose = { editing = null },
        )
    }
    photoEditing?.let { (i, tab) ->
        val tile = summary?.tiles?.getOrNull(i)
        if (material != null && summary != null && tile?.media != null) {
            val slot = vm.layout.slots[i]
            EditPhotoScreen(
                tile, material.pictures[tile.media], material.thumbnails[tile.media],
                aspect = (slot.w / slot.h * vm.layout.aspect).toFloat(),
                startTab = tab,
                gradeOf = material.gradeFor(summary),
                onDone = { vm.setTile(i, it); photoEditing = null },
                onCancel = { photoEditing = null },
            )
        }
    }
    picking?.let { onPicked ->
        if (material != null) PhotoPicker(material, many = pickMany, onDone = { ids -> picking = null; if (ids.isNotEmpty()) onPicked(ids) })
    }
    if (exporting && material != null && summary != null) {
        val look = rememberAlbumLook(material.trip, material.media, forExport = true)
        if (look != null) ExportSheet(
            material, summary, vm.layout, look, vm.songs, vm.miles,
            stops = remember(material.media) { planRoute(material.media.mapNotNull { it.toRoutePoint() }).stops.size },
            exporter = exporter,
            onClose = { exporting = false },
        )
    }
    if (renaming && summary != null) {
        var name by remember { mutableStateOf(summary.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this summary") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (summary.id != null) TextButton(onClick = { renaming = false; confirmingDelete = true }) {
                        Text("Delete summary", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("Delete this summary?") },
        text = { Text("The photos stay in the trip.") },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; vm.delete(onBack) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
    )
}

/**
 * Under the summary: how to select when nothing is, or how many are selected and what to do
 * with them. [onEditPhoto] is there when exactly one photo is selected.
 */
@Composable
private fun SelectionBar(selected: Set<Int>, slots: Int, onEditPhoto: (() -> Unit)?, onChange: (Set<Int>) -> Unit) {
    if (selected.isEmpty()) {
        Text(
            "Tap a tile to change it · hold to select · hold and drag across to select several",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            onEditPhoto?.let { TextButton(onClick = it) { Text("Edit photo") } }
            if (selected.size < slots) TextButton(onClick = { onChange((0 until slots).toSet()) }) { Text("Select all") }
            TextButton(onClick = { onChange(emptySet()) }) { Text("Clear") }
        }
        Text(
            "Hold a selected tile and drag to move them together",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- The editor's panels, one per tab ----------

/** The filter for every photo; the previews show the summary's first photo, drawn as the summary would draw it. */
@Composable
internal fun WholeSummaryFilter(summary: Summary, material: SummaryMaterial, onChange: (Look) -> Unit) {
    val look = summary.look?.toLook() ?: Look()
    val sample = summary.tiles.firstOrNull { it.media != null }
    FilterStrip("Filter · all photos", look, sample?.media?.let { material.thumbnails[it] }, { f ->
        sample?.let { material.gradeFor(summary.copy(look = look.copy(filter = f).toData()))(it) }
    }, onChange)
}

@Composable
internal fun StylePanel(mode: SummaryMode, summary: Summary, material: SummaryMaterial, onChoose: (SummaryMode) -> Unit) {
    SectionLabel("Style")
    val first = summary.colours.firstOrNull()?.let { rgbColor(hexToRgb(it)) } ?: MaterialTheme.colorScheme.outlineVariant
    val palette = material.palette.map { rgbColor(it.rgb) }
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in SummaryMode.entries) {
            val stripes = when (option) {
                SummaryMode.Monochrome -> listOf(first)
                SummaryMode.Palette -> palette.take(3)
                SummaryMode.Rainbow -> palette.take(5)
                SummaryMode.Manual -> emptyList()
            }
            StyleCard(
                if (option == SummaryMode.Manual) "My picks" else option.label, stripes, option == mode,
                Modifier.weight(1f),
            ) { onChoose(option) }
        }
    }
    if (mode == SummaryMode.Manual) Text("Tap My picks again to choose different photos.", style = MaterialTheme.typography.bodySmall)
}

/** A style choice: a small preview of its colours (dashed when you choose them yourself) and its name. */
@Composable
private fun StyleCard(label: String, stripes: List<Color>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(colours.surfaceContainerHigh)
            .border(2.dp, if (selected) colours.onSurface else Color.Transparent, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).then(
            if (stripes.isEmpty()) Modifier.dashedBorder(colours.outline) else Modifier,
        )) {
            for (c in stripes) Box(Modifier.weight(1f).fillMaxHeight().background(c))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

private fun Modifier.dashedBorder(colour: Color) = drawBehind {
    drawRoundRect(colour, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))), cornerRadius = CornerRadius(6.dp.toPx()))
}

@Composable
internal fun ColourPanel(mode: SummaryMode, summary: Summary, material: SummaryMaterial, onChoose: (Oklab) -> Unit) {
    // How many photos are mostly each colour: what a one-colour summary can be built from.
    val matching = remember(material) {
        (material.palette.map { it.rgb } + COLOUR_WHEEL.map { it.second }).associate { rgb ->
            rgbToHex(rgb) to material.candidates.count { share(it.swatches, Oklab.fromRgb(rgb)) >= MONOCHROME_SHARE }
        }
    }
    val chosen = summary.colours.firstOrNull().takeIf { mode == SummaryMode.Monochrome }
    SectionLabel(chosen?.let { "Colour · ${matching[it] ?: 0} photos match" } ?: "Colour")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        material.palette.forEachIndexed { i, swatch ->
            val hex = rgbToHex(swatch.rgb)
            StampSwatch(
                rgbColor(swatch.rgb), colourName(swatch.color), hex == chosen, onClick = { onChoose(swatch.color) },
                count = matching[hex], tilt = if (i % 2 == 0) -2f else 2f,
            )
        }
    }
    SectionLabel("More colours")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        COLOUR_WHEEL.forEachIndexed { i, (name, rgb) ->
            val hex = rgbToHex(rgb)
            val n = matching.getValue(hex)
            StampSwatch(
                rgbColor(rgb), name, hex == chosen, onClick = { onChoose(Oklab.fromRgb(rgb)) },
                count = n, enabled = n > 0, tilt = if (i % 2 == 0) -2f else 2f,
            )
        }
    }
    Text(
        if (chosen == null) "Pick a colour to make a one-colour summary from it."
        else "Places without a matching photo become colour tiles.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun LayoutPanel(chosen: Layout, onChoose: (Layout) -> Unit) {
    SectionLabel("Layout")
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (layout in Layout.entries) {
            val on = layout == chosen
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.width(72.dp).selectable(on, role = Role.RadioButton) { onChoose(layout) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LayoutPreview(layout, Modifier.size(60.dp).clip(shape).background(Color.White).border(2.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, shape).padding(8.dp))
                Text(layout.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** A layout's slots drawn as grey blocks, fitted into the box. */
@Composable
private fun LayoutPreview(layout: Layout, modifier: Modifier) {
    Canvas(modifier) {
        val aspect = layout.aspect.toFloat()
        val w = if (aspect >= 1) size.width else size.height * aspect
        val h = if (aspect >= 1) size.width / aspect else size.height
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        for (slot in layout.slots) {
            val x = left + slot.x.toFloat() * w
            val y = top + slot.y.toFloat() * h
            val sw = slot.w.toFloat() * w
            val sh = slot.h.toFloat() * h
            rotate(slot.rotation.toFloat(), pivot = Offset(x + sw / 2, y + sh / 2)) {
                drawRect(Color(0xFFA59E92), Offset(x, y), Size(sw, sh))
            }
        }
    }
}

// ---------- Tapping a tile ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TileSheet(
    index: Int,
    tile: SummaryTile,
    photo: TripMedia?,
    thumbnail: String?,
    tripId: String,
    slotCount: Int,
    colours: List<String>,
    quickTexts: List<String>,
    onChange: (SummaryTile) -> Unit,
    onMove: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    onEditPhoto: (PhotoTab) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(tile.bg?.let { rgbColor(hexToRgb(it)) } ?: MaterialTheme.colorScheme.surfaceVariant)) {
                    if (photo != null) Thumbnail(tripId, photo.id, photo.kind, thumbnail)
                }
                Column {
                    Text(
                        when {
                            photo != null -> "Photo by ${photo.profile?.displayName ?: "someone"}"
                            tile.text != null -> "Text tile"
                            else -> "Colour tile"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        listOfNotNull(photo?.localTime?.format(MOMENT), "tile ${index + 1} of $slotCount").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tile.media != null) {
                SheetRow("Edit photo", "Filter and adjust just this one") { onEditPhoto(PhotoTab.Filter) }
                SheetRow("Crop", "Choose what shows in the tile") { onEditPhoto(PhotoTab.Crop) }
                SheetRow("Replace photo", "Pick another from the trip", onPickPhoto)
                SheetRow("Make it a text tile", "Trip name, miles, dates or your words") {
                    onChange(SummaryTile(text = quickTexts.firstOrNull(), bg = colours.first()))
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(!tile.noGrade, role = Role.Switch) { onChange(tile.copy(noGrade = !it)) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Match colours on this photo", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text("Follows the summary's Match colours setting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = !tile.noGrade, onCheckedChange = null) // the whole row is the switch
                }
            } else {
                OutlinedTextField(
                    value = tile.text.orEmpty(),
                    onValueChange = { onChange(tile.copy(text = it.take(80).ifEmpty { null }, bg = tile.bg ?: colours.first())) },
                    label = { Text("Text (leave empty for a plain colour)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (text in quickTexts) InkChip(text, tile.text == text, { onChange(tile.copy(text = text, bg = tile.bg ?: colours.first())) })
                }
                SectionLabel("Colour")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (hex in colours) {
                        val rgb = hexToRgb(hex)
                        Box(
                            Modifier.size(40.dp)
                                .background(rgbColor(rgb), CircleShape)
                                .border(if (tile.bg == hex) 3.dp else 1.dp, if (tile.bg == hex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(onClickLabel = "Use ${colourName(Oklab.fromRgb(rgb))}") { onChange(tile.copy(bg = hex)) },
                        )
                    }
                }
                SheetRow("Use a photo instead", "Pick one from the trip", onPickPhoto)
            }
            // Buttons as well as dragging, so tiles can be moved without a long-press gesture.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onMove(index - 1) }, enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Move earlier") }
                OutlinedButton(onClick = { onMove(index + 1) }, enabled = index < slotCount - 1, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Move later") }
            }
        }
    }
}

/** A tall, easy-to-hit row in a sheet: what it does, and a line on what that means. */
@Composable
private fun SheetRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
