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
    )
    val newestFirst = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
    val items = mutableListOf<SpikeItem>()
    // `use` closes the cursor even if something below throws (Kotlin's try-with-resources).
    context.contentResolver.query(collection, columns, null, null, newestFirst)?.use { cursor ->
        while (items.size < limit && cursor.moveToNext()) {
            val uri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            // runCatching turns an exception into a Result so one unreadable file cannot stop the scan.
            val gps = runCatching { if (isVideo) videoGps(context, uri) else photoGps(context, uri) }
            items += SpikeItem(
                name = cursor.getString(1) ?: "(unnamed)",
                isVideo = isVideo,
                takenAt = cursor.getLong(2).takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(TIME_FORMAT) }
                    ?: "Unknown capture time",
                gps = gps.getOrNull(),
                problem = gps.exceptionOrNull()?.let { it::class.simpleName },
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

private fun photoGps(context: Context, uri: Uri): LatLng? =
    context.contentResolver.openInputStream(original(uri))?.use { stream ->
        ExifInterface(stream).latLong?.let { gpsOrNull(it[0], it[1]) }
    }

private fun videoGps(context: Context, uri: Uri): LatLng? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, original(uri))
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
    } finally {
        retriever.release()
    }
}
