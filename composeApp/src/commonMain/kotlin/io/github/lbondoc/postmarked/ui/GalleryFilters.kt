package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.notesToShow
import io.github.lbondoc.postmarked.data.OutboxEntry
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.COLOUR_WHEEL
import io.github.lbondoc.postmarked.domain.color.colourName
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.color.rgbToHex
import io.github.lbondoc.postmarked.domain.trip.TripArea
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format

// ---------- The choices a trip offers ----------

private val KINDS = listOf(MediaKind.Photo to "Photos", MediaKind.Video to "Videos")

// Highway green, postal red, stamp blue, mustard, plum: one per person, in name order.
private val PERSON_COLOURS = listOf(0xFF2F5D50, 0xFFB5452F, 0xFF1F5FA6, 0xFF8A6417, 0xFF6B4C8A).map(::Color)

private fun peopleOf(media: List<TripMedia>) =
    media.distinctBy { it.contributor }.map { it.contributor to (it.profile?.displayName ?: "Someone") }.sortedBy { it.second.lowercase() }

private fun personColour(people: List<Pair<String, String>>, id: String) =
    PERSON_COLOURS[people.indexOfFirst { it.first == id }.coerceAtLeast(0) % PERSON_COLOURS.size]

private fun daysOf(media: List<TripMedia>) = media.map { it.takenOn }.distinct().sorted()
private fun dayLabel(tripStart: LocalDate, day: LocalDate) = "Day ${tripStart.daysUntil(day) + 1}"
private fun placeLabel(name: String) = name.substringBefore(',')
private fun colourLabel(hex: String) = WHEEL.firstOrNull { it.second == hex }?.first ?: colourName(Oklab.fromRgb(hexToRgb(hex)))

/** The "More colours" row as "#RRGGBB", the form filters store colours in. */
internal val WHEEL = COLOUR_WHEEL.map { (name, rgb) -> name to rgbToHex(rgb) }

/** One switched-on filter, as a chip under the Filter button. */
private class ActiveFilter(val label: String, val dot: Color?, val remove: (GalleryFilter) -> GalleryFilter)

private fun activeFilters(filter: GalleryFilter, media: List<TripMedia>, tripStart: LocalDate): List<ActiveFilter> {
    val people = peopleOf(media)
    return filter.colours.map { hex -> ActiveFilter(colourLabel(hex), rgbColor(hexToRgb(hex))) { it.copy(colours = it.colours - hex) } } +
        filter.people.map { id ->
            ActiveFilter(people.firstOrNull { it.first == id }?.second ?: "Someone", personColour(people, id)) { it.copy(people = it.people - id) }
        } +
        filter.days.sorted().map { day -> ActiveFilter(dayLabel(tripStart, day), null) { it.copy(days = it.days - day) } } +
        filter.places.map { place -> ActiveFilter(placeLabel(place), null) { it.copy(places = it.places - place) } } +
        filter.kinds.map { kind -> ActiveFilter(KINDS.first { it.first == kind }.second, null) { it.copy(kinds = it.kinds - kind) } }
}

// ---------- On the trip page ----------

/**
 * The gallery part of the trip page: the arrange switch and filters (pinned to the top while
 * scrolling), then the grouped grid.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyGridScope.gallery(
    tripId: String,
    tripStart: LocalDate,
    vm: TripViewModel,
    stopNames: Map<Int, String>,
    onFilter: () -> Unit,
    /** Writes waiting to send, for day notes edited offline. */
    outbox: List<OutboxEntry>,
    onEditNote: (LocalDate) -> Unit,
    onOpen: (List<TripMedia>, Int) -> Unit,
) {
    val notes = notesToShow(vm.savedNotes, outbox, tripId)
    val waitingDays = outbox.filterIsInstance<OutboxEntry.DayNote>().filter { it.tripId == tripId }.map { it.day }.toSet()
    val canEdit = vm.trip?.myRole?.canEdit == true
    val visible = filterMedia(vm.media, vm.filter, vm.places)
    val sections = gallerySections(visible, vm.media, vm.view, tripStart, stopNames, notes)
    // The viewer swipes through everything shown, in order, including the shots folded behind each tile.
    val ordered = sections.flatMap { section -> section.tiles.flatMap { it.all } }

    stickyHeader(key = "gallery-controls") { GalleryControls(vm, tripStart, visible.size, onFilter) }
    if (vm.media.isNotEmpty() && visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
        Text("Nothing matches these filters.", Modifier.padding(16.dp))
    }
    for (section in sections) {
        section.title?.let { title ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "section:$title") {
                Column(Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    // By day: the day's note, editable in place by the owner and editors (SPEC §6).
                    val day = section.day
                    if (day != null) {
                        section.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (day in waitingDays) Text("Waiting to send", style = MaterialTheme.typography.bodySmall)
                        if (canEdit) TextButton(onClick = { onEditNote(day) }) { Text(if (section.note == null) "Add a note" else "Edit note") }
                    }
                }
            }
        }
        items(section.tiles, key = { it.cover.id }) { tile ->
            val selecting = vm.selected.isNotEmpty()
            val selected = tile.cover.id in vm.selected
            Box(
                Modifier.clickable(onClickLabel = if (selecting) "Select" else "Open") {
                    // While selecting, a tap adds or removes the tile instead of opening it.
                    if (selecting) vm.selected = vm.selected.toggled(tile.cover.id) else onOpen(ordered, ordered.indexOf(tile.cover))
                }.then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
            ) {
                Thumbnail(tripId, tile.cover.id, tile.cover.kind, vm.thumbnails[tile.cover.id])
                if (selected) Text(
                    "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (tile.others.isNotEmpty()) Text(
                    "+${tile.others.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun GalleryControls(vm: TripViewModel, tripStart: LocalDate, shown: Int, onFilter: () -> Unit) {
    val filter = vm.filter
    val colours = MaterialTheme.colorScheme
    Column(
        // Opaque, because the photos scroll underneath it.
        Modifier.fillMaxWidth().background(colours.background).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Arrange", style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
            Segmented(GalleryView.entries.map { it to it.label }, vm.view, { vm.view = it }, Modifier.weight(1f))
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterButton(filter.count, onFilter)
            for (active in remember(filter, vm.media, tripStart) { activeFilters(filter, vm.media, tripStart) }) {
                ActiveChip(active.label, active.dot) { vm.filter = active.remove(vm.filter) }
            }
        }
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (filter.isEmpty) "${vm.media.size} photos and videos" else "Showing $shown of ${vm.media.size}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant,
            )
            if (!filter.isEmpty) TextButton(onClick = { vm.filter = GalleryFilter() }) { Text("Clear all", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** The ink pill that opens the filter sheet, with a red badge counting the filters that are on. */
@Composable
private fun FilterButton(on: Int, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val paper = colours.surfaceContainerLowest
    Row(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(colours.onSurface)
            .clickable(onClickLabel = "Choose filters", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = if (on == 0) "Filter" else "Filter, $on on" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Three shortening lines: the usual "filter" mark.
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            for ((i, inset) in listOf(0f, 0.2f, 0.35f).withIndex()) {
                val y = size.height * (0.25f + i * 0.25f)
                drawLine(paper, Offset(w * inset, y), Offset(w * (1 - inset), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Filter", color = paper, fontWeight = FontWeight.SemiBold)
        if (on > 0) Box(
            Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).background(colours.primary, CircleShape).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) { Text("$on", color = colours.onPrimary, style = MaterialTheme.typography.labelSmall) }
    }
}

/** A switched-on filter; tapping it switches it off. */
@Composable
private fun ActiveChip(label: String, dot: Color?, onRemove: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(colours.surfaceContainerLowest).border(1.5.dp, colours.outline, shape)
            .clickable(onClickLabel = "Remove this filter", onClick = onRemove)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(14.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("✕", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

// ---------- The filter sheet ----------

/**
 * Every filter in one sheet, a section per kind. Each choice shows how many it would show, and
 * fades when that's none. Changes apply at once (the gallery behind updates); the button closes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(
    media: List<TripMedia>,
    places: Map<String, TripArea>,
    tripStart: LocalDate,
    filter: GalleryFilter,
    onChange: (GalleryFilter) -> Unit,
    onClose: () -> Unit,
) {
    val palette = remember(media) { tripPalette(media).map { rgbToHex(it.rgb) } }
    val people = remember(media) { peopleOf(media) }
    val days = remember(media) { daysOf(media) }
    val placeNames = remember(places) { places.keys.sorted() }
    // Counting reruns the filter once per choice: fine for a trip's few hundred photos.
    val counts = remember(media, places, filter) {
        object {
            val byColour = countEach(media, filter, places, (palette + WHEEL.map { it.second }).distinct()) { copy(colours = setOf(it)) }
            val byPerson = countEach(media, filter, places, people.map { it.first }) { copy(people = setOf(it)) }
            val byDay = countEach(media, filter, places, days) { copy(days = setOf(it)) }
            val byPlace = countEach(media, filter, places, placeNames) { copy(places = setOf(it)) }
            val byKind = countEach(media, filter, places, KINDS.map { it.first }) { copy(kinds = setOf(it)) }
            val total = filterMedia(media, filter, places).size
        }
    }
    fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Filter photos", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { onChange(GalleryFilter()) }, enabled = !filter.isEmpty) { Text("Clear all", fontWeight = FontWeight.SemiBold) }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // A section only appears when it offers a real choice.
            if (palette.size >= 2) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Colour")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    palette.forEachIndexed { i, hex ->
                        val n = counts.byColour.getValue(hex)
                        StampSwatch(
                            rgbColor(hexToRgb(hex)), colourLabel(hex), hex in filter.colours,
                            onClick = { onChange(filter.copy(colours = filter.colours.toggle(hex))) },
                            count = n, enabled = n > 0, tilt = if (i % 2 == 0) -2f else 2f,
                        )
                    }
                }
            }
            // Any colour, not only the trip's own: greyed out where no photo matches.
            if (media.any { !it.palette.isNullOrEmpty() }) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("More colours")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    WHEEL.forEachIndexed { i, (name, hex) ->
                        val n = counts.byColour.getValue(hex)
                        StampSwatch(
                            rgbColor(hexToRgb(hex)), name, hex in filter.colours,
                            onClick = { onChange(filter.copy(colours = filter.colours.toggle(hex))) },
                            count = n, enabled = n > 0 || hex in filter.colours, tilt = if (i % 2 == 0) -2f else 2f,
                        )
                    }
                }
            }
            if (people.size >= 2) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Taken by")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, name) in people) {
                        val n = counts.byPerson.getValue(id)
                        InkChip(
                            name, id in filter.people, { onChange(filter.copy(people = filter.people.toggle(id))) },
                            count = n, enabled = n > 0, leading = { Initial(name, personColour(people, id)) },
                        )
                    }
                }
            }
            if (days.size >= 2) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Day")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (day in days) {
                        val n = counts.byDay.getValue(day)
                        InkChip(
                            "${dayLabel(tripStart, day)} · ${day.format(DAY)}", day in filter.days,
                            { onChange(filter.copy(days = filter.days.toggle(day))) }, count = n, enabled = n > 0,
                        )
                    }
                }
            }
            if (placeNames.size >= 2) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Place")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (place in placeNames) {
                        val n = counts.byPlace.getValue(place)
                        InkChip(placeLabel(place), place in filter.places, { onChange(filter.copy(places = filter.places.toggle(place))) }, count = n, enabled = n > 0)
                    }
                }
            }
            if (media.any { it.kind == MediaKind.Video }) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((kind, label) in KINDS) {
                        val n = counts.byKind.getValue(kind)
                        InkChip(label, kind in filter.kinds, { onChange(filter.copy(kinds = filter.kinds.toggle(kind))) }, count = n, enabled = n > 0)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            InkButton(
                if (counts.total == 0) "Nothing matches — remove a filter" else "Show ${counts.total} of ${media.size}",
                onClick = onClose, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
