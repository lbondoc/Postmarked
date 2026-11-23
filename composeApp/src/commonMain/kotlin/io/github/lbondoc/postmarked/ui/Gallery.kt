package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.toRoutePoint
import io.github.lbondoc.postmarked.domain.media.Shot
import io.github.lbondoc.postmarked.domain.media.groupSimilar
import io.github.lbondoc.postmarked.domain.route.planRoute
import io.github.lbondoc.postmarked.domain.trip.TripArea
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format

/** Photos up to this far outside a named place still count as taken there (GPS wobble, the car park). */
private const val PLACE_MARGIN_METERS = 2_000.0

enum class GalleryView(val label: String) { All("All"), Days("Days"), Stops("Stops"), People("People") }

/** Empty sets mean "any". Different kinds of filter combine with AND, choices within one with OR. */
data class GalleryFilter(
    val kinds: Set<MediaKind> = emptySet(),
    val people: Set<String> = emptySet(),
    val days: Set<LocalDate> = emptySet(),
    val places: Set<String> = emptySet(),
) {
    val isEmpty get() = kinds.isEmpty() && people.isEmpty() && days.isEmpty() && places.isEmpty()
}

/** One grid square: the best of a group of near-identical shots, with the rest behind it. */
class Tile(val cover: TripMedia, val others: List<TripMedia>) {
    val all get() = listOf(cover) + others
}

class Section(val title: String?, val tiles: List<Tile>)

fun filterMedia(items: List<TripMedia>, filter: GalleryFilter, places: Map<String, TripArea>): List<TripMedia> =
    items.filter { item ->
        (filter.kinds.isEmpty() || item.kind in filter.kinds) &&
            (filter.people.isEmpty() || item.contributor in filter.people) &&
            (filter.days.isEmpty() || item.takenOn in filter.days) &&
            (filter.places.isEmpty() || filter.places.any { name ->
                val at = item.location
                at != null && places[name]?.covers(at, PLACE_MARGIN_METERS) == true
            })
    }

/**
 * Splits [items] into the sections of [view], with near-identical photos folded into one tile.
 * [allItems] is the whole trip: stops are worked out from everything, so filtering never renumbers them.
 */
fun gallerySections(items: List<TripMedia>, allItems: List<TripMedia>, view: GalleryView, tripStart: LocalDate): List<Section> =
    when (view) {
        GalleryView.All -> listOf(Section(null, tiles(items)))
        GalleryView.Days -> items.groupBy { it.takenOn }.entries.sortedBy { it.key }.map { (day, dayItems) ->
            Section("Day ${tripStart.daysUntil(day) + 1} · ${day.format(DAY)} · ${dayItems.size}", tiles(dayItems))
        }
        GalleryView.Stops -> {
            val stopOf = planRoute(allItems.mapNotNull { it.toRoutePoint() }).stops
                .flatMapIndexed { index, stop -> stop.ids.map { it to index } }.toMap()
            // Items with no location (or a glitchy one) have no stop; they go last.
            items.groupBy { stopOf[it.id] }.entries.sortedBy { it.key ?: Int.MAX_VALUE }.map { (stop, stopItems) ->
                Section(if (stop == null) "No location · ${stopItems.size}" else "Stop ${stop + 1} · ${stopItems.size}", tiles(stopItems))
            }
        }
        GalleryView.People -> items.groupBy { it.contributor }.values
            .sortedBy { it.first().profile?.displayName?.lowercase() }
            .map { personItems -> Section("${personItems.first().profile?.displayName ?: "Someone"} · ${personItems.size}", tiles(personItems)) }
    }

/** In time order; photos taken seconds apart that look alike share a tile, showing the sharpest. */
private fun tiles(items: List<TripMedia>): List<Tile> {
    val byId = items.associateBy { it.id }
    val (videos, photos) = items.partition { it.kind == MediaKind.Video }
    val photoGroups = groupSimilar(photos.map { Shot(it.id, it.takenAt.toEpochMilliseconds(), it.dhash, it.sharpness) })
        .map { group -> group.map { byId.getValue(it.id) } }
    return (photoGroups + videos.map { listOf(it) })
        .sortedBy { group -> group.minOf { it.takenAt } }
        .map { Tile(it.first(), it.drop(1)) }
}
