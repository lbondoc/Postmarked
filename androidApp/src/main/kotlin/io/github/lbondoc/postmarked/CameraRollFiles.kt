package io.github.lbondoc.postmarked

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.domain.geo.gpsOrNull
import io.github.lbondoc.postmarked.domain.geo.parseIso6709
import io.github.lbondoc.postmarked.domain.media.exifDate
import io.github.lbondoc.postmarked.domain.media.exifOffsetMinutes
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.datetime.toKotlinLocalDate

/**
 * The address of the file exactly as the camera wrote it. Android otherwise hands out a copy
 * with GPS blanked. Asking for the original without the media-location permission fails,
 * so then the blanked copy is the best there is.
 */
fun Context.originalUri(uri: Uri): Uri =
    if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        MediaStore.setRequireOriginal(uri)
    } else {
        uri
    }

fun Context.openOriginal(uri: Uri): InputStream =
    // A photo-picker link can't ask for the original (it throws); the file it opens is still the photo.
    runCatching { contentResolver.openInputStream(originalUri(uri)) }.getOrNull()
        ?: contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

/** One camera-roll item with its time and place read from the file. Blocking: call off the main thread. */
fun Context.readDeviceMedia(uri: Uri, kind: MediaKind, takenAtMs: Long): DeviceMedia {
    val phoneDay = Instant.ofEpochMilli(takenAtMs).atZone(ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()
    val plain = DeviceMedia(uri.toString(), kind, takenAtMs, phoneDay)
    // runCatching: an unreadable file still shows up, just without a location.
    return runCatching {
        when (kind) {
            MediaKind.Photo -> {
                val exif = openOriginal(uri).use { ExifInterface(it) }
                val gps = exif.latLong?.let { gpsOrNull(it[0], it[1]) }
                plain.copy(
                    takenOn = exifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) ?: phoneDay,
                    utcOffsetMinutes = exifOffsetMinutes(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)),
                    lat = gps?.lat,
                    lng = gps?.lng,
                )
            }
            // shortcut: videos carry no local clock time, so their day uses the phone's current time zone;
            // read the track's creation time and the location's zone if late-evening videos land on the wrong day.
            MediaKind.Video -> MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(this, originalUri(uri))
                val gps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
                plain.copy(lat = gps?.lat, lng = gps?.lng)
            }
        }
    }.getOrDefault(plain)
}
