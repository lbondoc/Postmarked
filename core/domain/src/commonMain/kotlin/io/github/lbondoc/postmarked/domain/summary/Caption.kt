package io.github.lbondoc.postmarked.domain.summary

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding

private val MONTH_DAY = LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED); chars(" "); day(Padding.NONE) }

/** "Oct 3 – 5, 2026", "Sep 30 – Oct 2, 2026", "Dec 30, 2026 – Jan 2, 2027", or "Oct 3, 2026" for one day. */
fun dateSpan(start: LocalDate, end: LocalDate): String = when {
    start == end -> "${start.format(MONTH_DAY)}, ${start.year}"
    start.year != end.year -> "${start.format(MONTH_DAY)}, ${start.year} – ${end.format(MONTH_DAY)}, ${end.year}"
    start.month == end.month -> "${start.format(MONTH_DAY)} – ${end.day}, ${end.year}"
    else -> "${start.format(MONTH_DAY)} – ${end.format(MONTH_DAY)}, ${end.year}"
}

/** A hashtag from a name: letters and digits only, lower case. "Big Sur" -> #bigsur. Null if nothing is left. */
fun hashtag(name: String): String? =
    name.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }?.let { "#$it" }

data class SongCredit(val title: String, val artist: String?)

/**
 * An Instagram caption for a trip: name, dates, miles and stops, places, the song (so it can be
 * credited; the music itself is added in Instagram), then up to [maxTags] hashtags.
 */
fun caption(
    tripName: String,
    start: LocalDate,
    end: LocalDate,
    miles: Int?,
    stops: Int,
    places: List<String>,
    song: SongCredit?,
    colour: String?,
    maxTags: Int = 8,
): String {
    val stats = listOfNotNull(
        dateSpan(start, end),
        miles?.takeIf { it > 0 }?.let { "$it miles" },
        stops.takeIf { it > 1 }?.let { "$it stops" },
    ).joinToString(" · ")
    val lines = listOfNotNull(
        tripName.trim(),
        stats,
        places.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "📍 "),
        song?.let { "🎵 " + listOfNotNull(it.title, it.artist?.takeIf(String::isNotBlank)).joinToString(" — ") },
    )
    val tags = (listOf("#roadtrip") + places.mapNotNull(::hashtag) + listOfNotNull(colour?.let { hashtag("${it}aesthetic") }) + "#postmarked")
        .distinct()
        .take(maxTags)
    return lines.joinToString("\n") + "\n\n" + tags.joinToString(" ")
}
