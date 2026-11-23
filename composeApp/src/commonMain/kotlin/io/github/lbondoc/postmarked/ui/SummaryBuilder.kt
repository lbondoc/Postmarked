package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.gradientOrder
import io.github.lbondoc.postmarked.domain.color.rainbowOrder
import io.github.lbondoc.postmarked.domain.color.rgbToHex
import io.github.lbondoc.postmarked.domain.color.share
import io.github.lbondoc.postmarked.domain.summary.Layout
import kotlin.math.roundToInt

/** At least this share of a photo in the chosen colour family to count as "that colour". */
const val MONOCHROME_SHARE = 0.2

enum class SummaryMode(val label: String) {
    Monochrome("One colour"),
    Palette("Trip palette"),
    Rainbow("Rainbow"),
    Manual("Manual"),
}

/**
 * Fills a layout for an automatic [mode]. Text slots get [title] on a colour tile; photo slots
 * get the best-fitting [candidates] (photos with palettes, one per burst). Monochrome slots
 * left over when too few photos match become plain colour tiles, as in a colour-themed grid.
 * [colours] is the summary's colours: the chosen colour first for monochrome.
 */
fun generateTiles(mode: SummaryMode, layout: Layout, candidates: List<TripMedia>, colours: List<Oklab>, title: String): List<SummaryTile> {
    require(mode != SummaryMode.Manual) { "Manual summaries are filled by hand" }
    val photoSlots = layout.slots.indices.count { it !in layout.textSlots }
    val usable = candidates.filter { !it.palette.isNullOrEmpty() }
    val palettes = usable.map { it.swatches }
    val picks: List<TripMedia> = when (mode) {
        SummaryMode.Monochrome -> usable.map { it to share(it.swatches, colours.first()) }
            .filter { it.second >= MONOCHROME_SHARE }
            .sortedByDescending { it.second }
            .take(photoSlots)
            .map { it.first }
        SummaryMode.Palette -> gradientOrder(palettes, colours.sortedBy { it.hue }, photoSlots).map { usable[it] }
        SummaryMode.Rainbow -> rainbowOrder(palettes).let { order ->
            // Spread the picks evenly from the red end to the far end, rather than taking only the reds.
            when {
                order.size <= photoSlots -> order
                photoSlots == 1 -> order.take(1)
                else -> List(photoSlots) { order[(it * (order.size - 1).toDouble() / (photoSlots - 1)).roundToInt()] }
            }
        }.map { usable[it] }
        SummaryMode.Manual -> emptyList()
    }
    val accent = rgbToHex(colours[if (mode == SummaryMode.Palette) colours.size / 2 else 0].toRgb())
    val next = picks.iterator()
    return layout.slots.indices.map { slot ->
        when {
            slot in layout.textSlots -> SummaryTile(text = title, bg = accent)
            next.hasNext() -> SummaryTile(media = next.next().id)
            mode == SummaryMode.Monochrome -> SummaryTile(bg = accent) // a plain colour square
            else -> SummaryTile()
        }
    }
}

/** Fills the photo slots, in order, with [picked]; text tiles stay where they are. */
fun fillManually(layout: Layout, current: List<SummaryTile>, picked: List<String>): List<SummaryTile> {
    val next = picked.iterator()
    return layout.slots.indices.map { slot ->
        val tile = current.getOrNull(slot) ?: SummaryTile()
        if (tile.text != null) tile else if (next.hasNext()) SummaryTile(media = next.next(), noGrade = tile.noGrade) else SummaryTile()
    }
}

/** Swaps the tiles in two slots. */
fun swapTiles(tiles: List<SummaryTile>, a: Int, b: Int): List<SummaryTile> =
    tiles.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
