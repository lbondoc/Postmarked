package io.github.lbondoc.postmarked.domain.media

import kotlinx.datetime.LocalDate

private val EXIF_DATE = Regex("""^(\d{4}):(\d{2}):(\d{2})""")
private val EXIF_OFFSET = Regex("""^([+-])(\d{2}):(\d{2})$""")

/**
 * The calendar day from an EXIF DateTimeOriginal value ("2026:10:03 14:22:01"). That value is
 * the clock time where the photo was taken, so this is the local day there, whatever time
 * zone the phone is in now. Null when missing or blank, which cameras write as zeros.
 */
fun exifDate(value: String?): LocalDate? {
    val (year, month, day) = EXIF_DATE.find(value?.trim() ?: return null)?.destructured ?: return null
    // runCatching: month 13 or day 0 make LocalDate throw.
    return runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
}

/** Minutes east of UTC from an EXIF OffsetTimeOriginal value ("-07:00" gives -420). */
fun exifOffsetMinutes(value: String?): Int? {
    val (sign, hours, minutes) = EXIF_OFFSET.find(value?.trim() ?: return null)?.destructured ?: return null
    val total = hours.toInt() * 60 + minutes.toInt()
    return if (total > 14 * 60 || minutes.toInt() >= 60) null else if (sign == "-") -total else total
}
