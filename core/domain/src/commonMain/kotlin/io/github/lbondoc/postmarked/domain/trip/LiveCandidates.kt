package io.github.lbondoc.postmarked.domain.trip

import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlinx.datetime.LocalDate

/** A camera-roll item as live mode sees it: [addedMs] is when it appeared on the phone (MediaStore's date added). */
data class LiveItem(val uri: String, val addedMs: Long, val takenOn: LocalDate, val location: LatLng?)

/**
 * What live mode queues by itself (SPEC §5): of the items added since [sinceMs], those [matchTrip] marks as
 * taken on this trip, both in its dates and in its area, the same ones the import screen ticks. Items already
 * queued ([queuedUris]) are skipped, so a rerun never adds a photo twice. Items with no location are left for
 * the person to add by hand: live mode only adds what is certainly from the trip.
 */
fun liveCandidates(
    items: List<LiveItem>,
    sinceMs: Long,
    queuedUris: Set<String>,
    start: LocalDate,
    end: LocalDate,
    area: TripArea,
): List<LiveItem> = items.filter { item ->
    item.addedMs > sinceMs &&
        item.uri !in queuedUris &&
        item.location != null &&
        matchTrip(item.takenOn, item.location, start, end, area) == TripMatch.Both
}
