package io.github.lbondoc.postmarked

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.gpsOrNull
import io.github.lbondoc.postmarked.domain.geo.parseIso6709
import io.github.lbondoc.postmarked.spike.SpikeItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

/** Newest [limitPerKind] photos and newest [limitPerKind] videos, with GPS read from each file. Blocking: call off the main thread. */
fun scanRecentMedia(context: Context, limitPerKind: Int = 150): List<SpikeItem> =
    scan(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, limitPerKind) +
        scan(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, limitPerKind)

private fun scan(context: Context, collection: Uri, isVideo: Boolean, limit: Int): List<SpikeItem> {
    val columns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATA, // the file path: deprecated, but a second way in for the diagnosis
    )
    val newestFirst = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
    val items = mutableListOf<SpikeItem>()
    // `use` closes the cursor even if something below throws (Kotlin's try-with-resources).
    context.contentResolver.query(collection, columns, null, null, newestFirst)?.use { cursor ->
        while (items.size < limit && cursor.moveToNext()) {
            val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            // runCatching turns an exception into a Result so one unreadable file cannot stop the scan.
            val path = cursor.getString(3)
            val reading = runCatching { if (isVideo) videoGps(context, uri, path) else photoGps(context, uri, path) }
                .getOrElse { Reading(null, "could not read: ${it::class.simpleName}") }
            items += SpikeItem(
                name = cursor.getString(1) ?: "(unnamed)",
                isVideo = isVideo,
                takenAt = cursor.getLong(2).takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(TIME_FORMAT) }
                    ?: "Unknown capture time",
                gps = reading.gps,
                note = reading.note,
            )
        }
    }
    return items
}

/**
 * Asks for the file exactly as the camera wrote it. Without this (and the ACCESS_MEDIA_LOCATION
 * permission) Android 10+ silently hands back a copy with the GPS tags blanked out.
 */
private fun original(uri: Uri): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri

/** A GPS read plus a note on how it was obtained, or why there is none. */
private class Reading(val gps: LatLng?, val note: String?)

private const val VIA_PATH = "read via file path; the original-file request gave nothing"

private fun ExifInterface.gps(): LatLng? = latLong?.let { gpsOrNull(it[0], it[1]) }

private fun photoGps(context: Context, uri: Uri, path: String?): Reading {
    // ExifInterface reads everything in its constructor, so the stream can close right after.
    val exif = context.contentResolver.openInputStream(original(uri))?.use { ExifInterface(it) }
    exif?.gps()?.let { return Reading(it, null) }
    path?.let { runCatching { ExifInterface(it).gps() }.getOrNull() }?.let { return Reading(it, VIA_PATH) }
    // When Android withholds a location it blanks the values but leaves the tag in place,
    // which is how a hidden location can be told apart from one that was never recorded.
    return Reading(
        gps = null,
        note = when {
            exif?.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null -> "location is in the file but Android blanked it"
            exif?.getAttribute(ExifInterface.TAG_MODEL) != null -> "camera saved no location in this file"
            else -> "file has no camera metadata at all"
        },
    )
}

private fun videoGps(context: Context, uri: Uri, path: String?): Reading {
    fun locationFrom(setSource: MediaMetadataRetriever.() -> Unit): LatLng? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setSource()
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
        } finally {
            retriever.release()
        }
    }
    locationFrom { setDataSource(context, original(uri)) }?.let { return Reading(it, null) }
    path?.let { runCatching { locationFrom { setDataSource(it) } }.getOrNull() }?.let { return Reading(it, VIA_PATH) }
    return Reading(null, "no location in the video file")
}
