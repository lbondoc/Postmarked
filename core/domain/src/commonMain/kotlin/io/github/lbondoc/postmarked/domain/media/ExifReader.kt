package io.github.lbondoc.postmarked.domain.media

import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.gpsOrNull
import kotlinx.datetime.LocalDateTime

/**
 * When and where a JPEG says it was taken. The text fields are EXIF's own values, for [exifDate],
 * [exifOffsetMinutes] and [exifDateTime]. Each field is null when missing or unreadable.
 */
data class ExifInfo(
    /** DateTimeOriginal, e.g. "2026:10:03 14:22:01": the clock time where it was taken. */
    val dateTimeOriginal: String? = null,
    /** OffsetTimeOriginal, e.g. "-07:00". Many cameras leave it out. */
    val offsetTimeOriginal: String? = null,
    val gps: LatLng? = null,
)

/** The clock time in an EXIF date value ("2026:10:03 14:22:01"), or null when missing or invalid. */
fun exifDateTime(value: String?): LocalDateTime? {
    val (y, mo, d, h, mi, s) = EXIF_DATE_TIME.find(value?.trim() ?: return null)?.destructured ?: return null
    return runCatching { LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull()
}

private val EXIF_DATE_TIME = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

/**
 * Reads the date and GPS position from a JPEG's EXIF block, for platforms with no EXIF library (the web, later
 * iOS). Only the start of the file is needed: EXIF comes before the picture, so the first 128 KB is plenty.
 * Never throws. A truncated or odd file gives whatever could be read, and anything else gives an empty result.
 */
fun readExif(jpeg: ByteArray): ExifInfo {
    if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8) return ExifInfo()
    var at = 2
    // Walk the segments before the image data, looking for APP1 "Exif\0\0".
    while (at + 4 <= jpeg.size && jpeg.u8(at) == 0xFF) {
        val marker = jpeg.u8(at + 1)
        if (marker == 0xD9 || marker == 0xDA) break // end of image, or start of the compressed picture
        val length = (jpeg.u8(at + 2) shl 8) or jpeg.u8(at + 3) // counts its own two bytes
        if (length < 2) break
        if (marker == 0xE1 && jpeg.startsWithAt(at + 4, EXIF_HEADER)) {
            val start = at + 4 + EXIF_HEADER.size
            return Tiff(jpeg, start, minOf(jpeg.size, at + 2 + length)).read()
        }
        at += 2 + length
    }
    return ExifInfo()
}

private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"

private const val TAG_EXIF_IFD = 0x8769
private const val TAG_GPS_IFD = 0x8825
private const val TAG_DATE_TIME_ORIGINAL = 0x9003
private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
private const val TAG_GPS_LATITUDE_REF = 1
private const val TAG_GPS_LATITUDE = 2
private const val TAG_GPS_LONGITUDE_REF = 3
private const val TAG_GPS_LONGITUDE = 4
private const val TYPE_ASCII = 2
private const val TYPE_RATIONAL = 5

/** One IFD entry: its type, how many values, and where they are (in place when they fit in 4 bytes). */
private class Entry(val type: Int, val count: Int, val valueAt: Int)

/** The TIFF structure inside the EXIF block. Offsets in it count from [base]; reads past [end] give null. */
private class Tiff(private val bytes: ByteArray, private val base: Int, private val end: Int) {
    private var little = false

    fun read(): ExifInfo {
        little = when {
            bytes.startsWithAt(base, byteArrayOf(0x49, 0x49)) -> true // "II", Intel order
            bytes.startsWithAt(base, byteArrayOf(0x4D, 0x4D)) -> false // "MM", Motorola order
            else -> return ExifInfo()
        }
        if (u16(base + 2) != 42) return ExifInfo()
        val ifd0 = entries(u32(base + 4) ?: return ExifInfo())
        val exif = ifd0[TAG_EXIF_IFD]?.let { u32(it.valueAt) }?.let(::entries).orEmpty()
        val gps = ifd0[TAG_GPS_IFD]?.let { u32(it.valueAt) }?.let(::entries).orEmpty()
        return ExifInfo(
            dateTimeOriginal = exif[TAG_DATE_TIME_ORIGINAL]?.let(::ascii),
            offsetTimeOriginal = exif[TAG_OFFSET_TIME_ORIGINAL]?.let(::ascii),
            gps = position(gps),
        )
    }

    private fun position(gps: Map<Int, Entry>): LatLng? {
        val lat = gps[TAG_GPS_LATITUDE]?.let(::degrees) ?: return null
        val lng = gps[TAG_GPS_LONGITUDE]?.let(::degrees) ?: return null
        val south = gps[TAG_GPS_LATITUDE_REF]?.let(::ascii) == "S"
        val west = gps[TAG_GPS_LONGITUDE_REF]?.let(::ascii) == "W"
        return gpsOrNull(if (south) -lat else lat, if (west) -lng else lng)
    }

    /** Degrees, minutes and seconds (three rationals) as decimal degrees. */
    private fun degrees(entry: Entry): Double? {
        if (entry.type != TYPE_RATIONAL || entry.count < 3) return null
        val at = u32(entry.valueAt) ?: return null // 24 bytes never fit in place
        val (d, m, s) = (0 until 3).map { rational(at + it * 8) ?: return null }
        return d + m / 60 + s / 3600
    }

    private fun rational(offset: Int): Double? {
        val numerator = u32(base + offset) ?: return null
        val denominator = u32(base + offset + 4) ?: return null
        return if (denominator == 0) null else numerator.toDouble() / denominator
    }

    private fun ascii(entry: Entry): String? {
        if (entry.type != TYPE_ASCII || entry.count <= 0) return null
        val at = if (entry.count <= 4) entry.valueAt else base + (u32(entry.valueAt) ?: return null)
        if (at < base || at + entry.count > end) return null
        return bytes.decodeToString(at, at + entry.count).substringBefore('\u0000').ifBlank { null }
    }

    /** The entries of the IFD at [offset], by tag. Stops at the first entry that runs past the end. */
    private fun entries(offset: Int): Map<Int, Entry> {
        val at = base + offset
        val count = u16(at) ?: return emptyMap()
        val found = mutableMapOf<Int, Entry>()
        for (i in 0 until count) {
            val e = at + 2 + i * 12
            val tag = u16(e) ?: break
            val type = u16(e + 2) ?: break
            val n = u32(e + 4) ?: break
            if (e + 12 > end) break
            found[tag] = Entry(type, n, e + 8)
        }
        return found
    }

    private fun u16(at: Int): Int? {
        if (at < base || at + 2 > end) return null
        val a = bytes.u8(at)
        val b = bytes.u8(at + 1)
        return if (little) a or (b shl 8) else (a shl 8) or b
    }

    /** Unsigned 32-bit as Int; offsets and counts over 2 GB can't occur in a JPEG, so negatives mean junk. */
    private fun u32(at: Int): Int? {
        val hi = u16(if (little) at + 2 else at) ?: return null
        val lo = u16(if (little) at else at + 2) ?: return null
        return ((hi shl 16) or lo).takeIf { it >= 0 }
    }
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

private fun ByteArray.startsWithAt(at: Int, prefix: ByteArray) =
    at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }
