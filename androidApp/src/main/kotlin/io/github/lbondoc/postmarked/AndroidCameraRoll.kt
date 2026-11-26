package io.github.lbondoc.postmarked

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.LiveMode
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.QueuedUpload
import io.github.lbondoc.postmarked.data.ScanResult
import io.github.lbondoc.postmarked.data.pickedMedia
import io.github.lbondoc.postmarked.domain.media.ExifInfo
import io.github.lbondoc.postmarked.domain.media.readExif
import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.datetime.TimeZone
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val MAX_PICKED = 100 // a picker limit, so one pick stays reviewable
private const val EXIF_BYTES = 128 * 1024 // EXIF sits at the start of a JPEG; this is far more than it needs

/** The camera roll for one Activity. Must be created in onCreate, because it registers for permission results. */
class AndroidCameraRoll(private val activity: ComponentActivity) : CameraRoll {

    private val permissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // Android 14+: lets people share only some photos ("limited access") instead of all or nothing.
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION) // without it every location reads as blank
        // Android 13+: lets the upload progress show in the notification shade. Saying no doesn't stop uploads.
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private var waiting: CancellableContinuation<Unit>? = null
    private val ask = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waiting?.resume(Unit)
        waiting = null
    }

    // "Add from anywhere": the system photo picker. It needs no permission and shows every photo and video.
    private var picking: CancellableContinuation<List<Uri>>? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris ->
        picking?.resume(uris)
        picking = null
    }

    override val canPickAnywhere get() = true

    override suspend fun pickAnywhere(): ScanResult? {
        val uris = suspendCancellableCoroutine { continuation ->
            picking = continuation
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        if (uris.isEmpty()) return null
        val items = withContext(Dispatchers.IO) { uris.mapNotNull(::readPicked) }
        return ScanResult(items.sortedBy { it.capturedAtMs }, locationAllowed = true)
    }

    /** A picked file, read like the website reads one: its EXIF date and GPS, else the file's date as a suggestion. */
    private fun readPicked(uri: Uri): DeviceMedia? {
        val resolver = activity.contentResolver
        // Uploads run later in the background, maybe after a restart: keep the right to read the file.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val kind = if (resolver.getType(uri)?.startsWith("video/") == true) MediaKind.Video else MediaKind.Photo
        val modifiedMs = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) * 1000 else null // stored in seconds
            }
        }.getOrNull() ?: 0L
        val exif = if (kind == MediaKind.Photo) {
            runCatching { resolver.openInputStream(uri)?.use { readExif(it.start(EXIF_BYTES)) } }.getOrNull() ?: ExifInfo()
        } else ExifInfo()
        return pickedMedia(uri.toString(), kind, exif, modifiedMs, TimeZone.currentSystemDefault())
    }

    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasPhotoAccess() = when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override suspend fun requestAccess(): Boolean {
        val missing = permissions.filterNot(::granted)
        if (missing.isNotEmpty()) suspendCancellableCoroutine { continuation ->
            waiting = continuation
            ask.launch(missing.toTypedArray())
        }
        return hasPhotoAccess()
    }

    override suspend fun scan(fromMs: Long, untilMs: Long): ScanResult = withContext(Dispatchers.IO) {
        val items = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.Photo, fromMs, untilMs) +
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.Video, fromMs, untilMs)
        ScanResult(items.sortedBy { it.capturedAtMs }, granted(Manifest.permission.ACCESS_MEDIA_LOCATION))
    }

    private fun query(collection: Uri, kind: MediaKind, fromMs: Long, untilMs: Long): List<DeviceMedia> {
        val found = mutableListOf<DeviceMedia>()
        activity.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN),
            "${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?",
            arrayOf(fromMs.toString(), untilMs.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                found += activity.readDeviceMedia(uri, kind, cursor.getLong(1))
            }
        }
        return found
    }

    override val uploads: StateFlow<List<QueuedUpload>> = UploadQueue.items

    override val uploadProgress: StateFlow<Map<String, Float>> = UploadQueue.progress

    override fun upload(tripId: String, items: List<DeviceMedia>) = UploadQueue.add(activity.applicationContext, tripId, items)

    override fun cancel(mediaIds: Set<String>) = UploadQueue.cancel(activity.applicationContext, mediaIds)

    override val live: LiveMode = AndroidLiveMode(activity.applicationContext)

    override val mobileData: StateFlow<Boolean> = UploadQueue.mobileData

    override fun allowMobileData(allowed: Boolean) = UploadQueue.allowMobileData(activity.applicationContext, allowed)
}

/** Up to [count] bytes from the start of the stream (InputStream.readNBytes needs Android 13). */
private fun java.io.InputStream.start(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) break
        read += n
    }
    return buffer.copyOf(read)
}
