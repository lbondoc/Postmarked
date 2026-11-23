package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.media.ExifInfo
import io.github.lbondoc.postmarked.domain.media.exifDate
import io.github.lbondoc.postmarked.domain.media.exifDateTime
import io.github.lbondoc.postmarked.domain.media.exifOffsetMinutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// Rules for files picked in a browser, which uploads them as they are: it can't convert videos or decode HEIC.

/** Supabase's free plan refuses files over 50 MB (SPEC: free tiers only). */
const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

/** The same words the phone shows when Storage refuses a file for its size. */
const val TOO_BIG = "Too big for the storage plan's upload limit."

const val HEIC_REFUSED = "HEIC photos can't be opened in a browser. Add them from the phone app, or save them as JPEG first."

private val HEIC_TYPES = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")
private val HEIC_EXTENSIONS = setOf("heic", "heif")

private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

/** Photo or video from the file's type, or null for anything else. Some browsers leave HEIC's type empty. */
fun pickedKind(name: String, mimeType: String): MediaKind? = when {
    mimeType.startsWith("video/") -> MediaKind.Video
    mimeType.startsWith("image/") || extension(name) in HEIC_EXTENSIONS -> MediaKind.Photo
    else -> null
}

/** Why a picked file can't be uploaded, or null when it can. */
fun pickedFileProblem(name: String, mimeType: String, sizeBytes: Long): String? = when {
    mimeType.lowercase() in HEIC_TYPES || extension(name) in HEIC_EXTENSIONS -> HEIC_REFUSED
    sizeBytes > MAX_UPLOAD_BYTES -> TOO_BIG
    else -> null
}

/**
 * A picked file as the import screen sees it. The time and place come from its EXIF when it has some, as on
 * the phone. Otherwise it gets the file's own date, which a camera's or phone's export keeps as the time taken.
 * An EXIF time without an offset is read in [zone], as the phone reads it.
 */
fun pickedMedia(uri: String, kind: MediaKind, exif: ExifInfo, lastModifiedMs: Long, zone: TimeZone): DeviceMedia {
    val offset = exifOffsetMinutes(exif.offsetTimeOriginal)
    val clock = exifDateTime(exif.dateTimeOriginal)
    val takenAt = when {
        clock == null -> Instant.fromEpochMilliseconds(lastModifiedMs)
        offset != null -> clock.toInstant(UtcOffset(minutes = offset))
        else -> clock.toInstant(zone)
    }
    val gps: LatLng? = exif.gps
    return DeviceMedia(
        uri = uri,
        kind = kind,
        capturedAtMs = takenAt.toEpochMilliseconds(),
        takenOn = exifDate(exif.dateTimeOriginal) ?: takenAt.toLocalDateTime(zone).date,
        utcOffsetMinutes = offset,
        lat = gps?.lat,
        lng = gps?.lng,
    )
}
