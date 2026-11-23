package io.github.lbondoc.postmarked.domain.summary

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding

/** Long enough for a sentence or two; Instagram cuts captions to two lines in the feed. */
private const val NOTE_QUOTE_MAX = 150

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

/** [text] in curly quotes, cut at a word to fit in [max] characters with "…" when it is longer. */
fun shortQuote(text: String, max: Int): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    val room = max - 2 // the quote marks
    if (clean.length <= room) return "“$clean”"
    val cut = clean.take(room - 1).substringBeforeLast(' ').ifEmpty { clean.take(room - 1) }
    return "“${cut.trimEnd(',', '.', ';', ':', ' ')}…”"
}

/**
 * The day note a summary's caption quotes: the one note among the days its photos come from. None when those
 * days have no note, or several (then there's no single day to speak for).
 */
fun noteToQuote(days: Set<LocalDate>, notes: Map<LocalDate, String>): String? =
    notes.filterKeys { it in days }.values.filter { it.isNotBlank() }.singleOrNull()

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
    /** A day note to quote under the stats ([noteToQuote]). */
    note: String? = null,
): String {
    val stats = listOfNotNull(
        dateSpan(start, end),
        miles?.takeIf { it > 0 }?.let { "$it miles" },
        stops.takeIf { it > 1 }?.let { "$it stops" },
    ).joinToString(" · ")
    val lines = listOfNotNull(
        tripName.trim(),
        stats,
        note?.takeIf { it.isNotBlank() }?.let { shortQuote(it, NOTE_QUOTE_MAX) },
        places.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "📍 "),
        song?.let { "🎵 " + listOfNotNull(it.title, it.artist?.takeIf(String::isNotBlank)).joinToString(" — ") },
    )
    val tags = (listOf("#roadtrip") + places.mapNotNull(::hashtag) + listOfNotNull(colour?.let { hashtag("${it}aesthetic") }) + "#postmarked")
        .distinct()
        .take(maxTags)
    return lines.joinToString("\n") + "\n\n" + tags.joinToString(" ")
}
