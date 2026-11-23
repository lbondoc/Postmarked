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
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
