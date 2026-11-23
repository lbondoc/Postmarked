package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.TripMedia
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format

/**
 * The viewer's info panel (SPEC §3.1), one line each: who took it, the local time, "Day N", the stop, the place,
 * then pixel and file size for photos or length and file size for videos. Anything not known is left out
 * rather than shown blank. Camera model isn't stored, so it never appears.
 */
fun infoLines(
    item: TripMedia,
    tripStart: LocalDate?,
    stop: String?,
    place: String?,
    pixels: Pair<Int, Int>? = null,
    bytes: Long? = null,
    seconds: Double? = null,
): List<String> {
    val day = tripStart?.let { it.daysUntil(item.takenOn) + 1 }?.takeIf { it >= 1 }
    val size = when (item.kind) {
        MediaKind.Photo -> listOfNotNull(pixels?.let { (w, h) -> "$w × $h px" }, bytes?.let(::fileSize))
        MediaKind.Video -> listOfNotNull(seconds?.let(::length), bytes?.let(::fileSize))
    }.joinToString(" · ").ifEmpty { null }
    return listOfNotNull(
        item.profile?.displayName?.let { "Taken by $it" },
        item.localTime.format(MOMENT),
        day?.let { "Day $it" },
        stop,
        place?.let { "Near $it" },
        size,
    )
}

private fun fileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${(bytes / 100_000.0).roundToInt() / 10.0} MB"
    else -> "${(bytes / 1000.0).roundToInt().coerceAtLeast(1)} KB"
}

private fun length(seconds: Double): String {
    val s = seconds.roundToInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
