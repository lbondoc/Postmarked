package io.github.lbondoc.postmarked

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.StorageRestException
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.uploadAsFlow
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.MediaKind.Companion.DISPLAY
import io.github.lbondoc.postmarked.data.MediaKind.Companion.ORIGINAL
import io.github.lbondoc.postmarked.data.MediaKind.Companion.POSTER
import io.github.lbondoc.postmarked.data.MediaKind.Companion.THUMB
import io.github.lbondoc.postmarked.data.MediaKind.Companion.VIDEO
import io.github.lbondoc.postmarked.data.QueuedUpload
import io.github.lbondoc.postmarked.data.batchProgress
import io.github.lbondoc.postmarked.domain.media.differenceHash
import io.github.lbondoc.postmarked.domain.media.sharpness
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.data.supabase
import io.github.lbondoc.postmarked.data.userMessage
import io.ktor.http.ContentType
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val DISPLAY_PX = 2048
private const val THUMB_PX = 400
private const val JPEG = "image/jpeg"
// About 45 MB a minute. shortcut: Supabase's free plan refuses files over 50 MB, so videos over
// roughly a minute fail with a clear message; raise the limit in Storage settings on a paid plan.
private const val VIDEO_BITRATE = 6_000_000
// Android stops ordinary background work after 10 minutes, so a run that couldn't become a
// foreground service (one started while the app was closed) hands over to a fresh run before that.
private const val TIME_BUDGET_MS = 8 * 60_000L
// Items uploaded at once. Each upload waits on the network between requests; three in parallel fill those gaps.
private const val PARALLEL_UPLOADS = 3
private const val CHANNEL_ID = "uploads"
private const val NOTIFICATION_ID = 1
// Temporary-looking failures in a row before an item is set aside so it can't block the queue.
private const val MAX_ATTEMPTS = 8

/** A file to upload and the type the server should store it as. */
private class Upload(val file: File, val contentType: String)

/** A problem that retrying won't fix. */
private class Unusable(message: String) : Exception(message)

/** Works through UploadQueue one item at a time, in the background, when the network rule allows. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (UploadQueue.items.value.none { it.waiting }) return Result.success()
        supabase.auth.awaitInitialization() // loads the saved sign-in from disk
        if (myUserId == null) return Result.retry() // the session couldn't be refreshed, e.g. offline

        // As a foreground service (with its notification) Android runs this promptly and without a time limit.
        val foreground = showProgress()
        val stopAt = if (foreground) Long.MAX_VALUE else SystemClock.elapsedRealtime() + TIME_BUDGET_MS
        val claimed = mutableSetOf<String>()
        val claimLock = Mutex()
        var retryLater = false

        // Several lanes, each taking the next item nobody has claimed until none are left.
        coroutineScope {
            repeat(PARALLEL_UPLOADS) {
                launch {
                    while (SystemClock.elapsedRealtime() < stopAt) {
                        val next = claimLock.withLock {
                            UploadQueue.items.value.firstOrNull { it.waiting && it.mediaId !in claimed }?.also { claimed += it.mediaId }
                        } ?: return@launch
                        // A failed item stays claimed, so no other lane picks it straight back up.
                        if (!uploadOrRecordProblem(next)) {
                            retryLater = true
                            return@launch
                        }
                        if (foreground) showProgress()
                    }
                }
            }
        }
        return when {
            retryLater -> Result.retry() // WorkManager tries again in about 30 seconds
            UploadQueue.items.value.any { it.waiting } -> {
                UploadWork.start(applicationContext) // time ran out: queued behind this run, so it starts as this one ends
                Result.success()
            }
            else -> Result.success()
        }
    }

    /** Uploads one item. False when it failed in a way worth retrying later. */
    private suspend fun uploadOrRecordProblem(next: QueuedUpload): Boolean = try {
        upload(next)
        true
    } catch (e: CancellationException) {
        throw e // Android stopped the work (Wi-Fi dropped, battery saver); it resumes later
    } catch (e: Exception) {
        // Re-read: the attempt may have recorded uploaded files before failing.
        val current = UploadQueue.items.value.firstOrNull { it.mediaId == next.mediaId }
        val problem = e.permanentProblem()
        when {
            current == null -> true
            problem == null && current.attempts + 1 < MAX_ATTEMPTS -> {
                UploadQueue.save(current.copy(attempts = current.attempts + 1))
                false
            }
            else -> {
                UploadQueue.save(current.copy(problem = problem ?: "Kept failing: ${e.userMessage()}"))
                scratch(current).deleteRecursively()
                true
            }
        }
    } finally {
        UploadQueue.progress.update { it - next.mediaId }
    }

    /** Shows or updates the upload notification. False when Android won't allow it (app started in the background). */
    private suspend fun showProgress(): Boolean {
        val progress = batchProgress(UploadQueue.items.value, UploadQueue.progress.value) ?: return false
        val notifications = NotificationManagerCompat.from(applicationContext)
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW).setName("Uploads").build()
        )
        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to Postmarked")
            .setContentText("${progress.done} of ${progress.total}")
            .setProgress(progress.total, progress.done, false)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // Android 12+ refuses to start one from the background; carry on as ordinary background work
        }
    }

    private suspend fun upload(start: QueuedUpload) {
        val uri = start.item.uri.toUri()
        val sha256 = withContext(Dispatchers.IO) { applicationContext.openOriginal(uri).use(::sha256) }
        if (Media.exists(start.tripId, sha256)) return finish(start) // someone already added this exact file

        val dir = scratch(start).apply { mkdirs() }
        var entry = start
        val isPhoto = start.item.kind == MediaKind.Photo
        // `lazy`: decoded once, on first use, and only if something still needs the picture.
        val picture = lazy { if (isPhoto) decodeScaled(ImageDecoder.createSource(applicationContext.contentResolver, uri), DISPLAY_PX) else posterFrame(uri).scaledTo(DISPLAY_PX) }
        val files = if (isPhoto) photoFiles(uri, dir, picture) else videoFiles(uri, dir, picture)
        fun report(fraction: Float) = UploadQueue.progress.update { it + (start.mediaId to fraction) }
        files.forEachIndexed { index, (name, produce) ->
            if (name in entry.uploadedFiles) return@forEachIndexed
            val upload = withContext(Dispatchers.IO) { produce() }
            supabase.storage.from("media").uploadAsFlow(Media.path(entry.tripId, entry.mediaId, name), upload.file) {
                upsert = true // a retry overwrites a half-finished earlier attempt
                contentType = ContentType.parse(upload.contentType)
            }.collect { status ->
                // Each file is an equal share of the item's bar; the original or the video dominates in practice.
                if (status is UploadStatus.Progress && status.contentLength > 0) {
                    report((index + status.totalBytesSend.toFloat() / status.contentLength) / files.size)
                }
            }
            report((index + 1f) / files.size)
            entry = entry.copy(uploadedFiles = entry.uploadedFiles + name)
            UploadQueue.save(entry)
        }
        // Only photos are grouped into bursts, so only they need a fingerprint and sharpness.
        val (dhash, sharpness) = if (isPhoto) withContext(Dispatchers.IO) { fingerprint(picture.value) } else null to null
        if (!Media.add(entry, sha256, dhash, sharpness)) {
            // A friend's copy of the same file got in while ours was uploading: drop ours.
            Media.deleteFiles(entry.item.kind.files.map { Media.path(entry.tripId, entry.mediaId, it) })
        }
        finish(entry)
    }

    private suspend fun finish(entry: QueuedUpload) {
        UploadQueue.save(entry.copy(finished = true, attempts = 0))
        scratch(entry).deleteRecursively()
    }

    // `lazy` decodes the photo once, on first use, and only if a file that needs it is still to upload.
    private fun photoFiles(uri: Uri, dir: File, display: Lazy<Bitmap>): List<Pair<String, suspend () -> Upload>> {
        return listOf(
            ORIGINAL to {
                val copy = File(dir, ORIGINAL)
                applicationContext.openOriginal(uri).use { input -> copy.outputStream().use { input.copyTo(it) } }
                Upload(copy, applicationContext.contentResolver.getType(uri) ?: JPEG)
            },
            DISPLAY to { Upload(display.value.jpeg(File(dir, DISPLAY), quality = 85), JPEG) },
            THUMB to { Upload(display.value.scaledTo(THUMB_PX).jpeg(File(dir, THUMB), quality = 80), JPEG) },
        )
    }

    private fun videoFiles(uri: Uri, dir: File, poster: Lazy<Bitmap>): List<Pair<String, suspend () -> Upload>> {
        return listOf(
            VIDEO to { Upload(transcode(uri, File(dir, VIDEO)), "video/mp4") },
            POSTER to { Upload(poster.value.jpeg(File(dir, POSTER), quality = 85), JPEG) },
            THUMB to { Upload(poster.value.scaledTo(THUMB_PX).jpeg(File(dir, THUMB), quality = 80), JPEG) },
        )
    }

    /** Re-encodes to H.264 at 1080p at most, in SDR so every browser and phone can play it. */
    // Media3 marks its video converter "unstable": its API may change between versions, so upgrades may need edits here.
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private suspend fun transcode(uri: Uri, out: File): File = converting.withLock {
        if (out.exists()) return out // converted on an earlier attempt; only the upload failed
        val shortSide = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(applicationContext, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            min(width, height)
        }
        // Only ever shrink: a 720p clip stays 720p.
        val effects = if (shortSide > 1080) Effects(emptyList(), listOf(Presentation.createForShortSide(1080))) else Effects.EMPTY
        // shortcut: the deprecated builder is the one that works out audio/video tracks from the file itself,
        // so clips without sound still convert; switch when Media3 removes it (it will fail to compile).
        @Suppress("DEPRECATION")
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()).build()
        ).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()

        // Written under another name and renamed when complete, so a half-written file is never mistaken for a finished one.
        val partial = File(out.parentFile, "partial.mp4").apply { delete() }
        // Transformer must be driven from a thread with a message loop; the main thread has one.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(applicationContext)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) =
                            continuation.resume(Unit)

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                            continuation.resumeWithException(exportException)
                    })
                    .build()
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                transformer.start(composition, partial.path)
            }
        }
        if (!partial.renameTo(out)) throw IOException("Couldn't save the converted video")
        out
    }

    /** A frame about a second in (or halfway, for shorter clips), already turned upright by Android. */
    private fun posterFrame(uri: Uri): Bitmap = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(applicationContext, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        retriever.getFrameAtTime(min(1_000, durationMs / 2) * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: throw Unusable("This video has no picture to show.")
    }

    private fun scratch(entry: QueuedUpload) = File(scratchRoot(applicationContext), entry.mediaId)

    companion object {
        /** One video conversion at a time: phones have few hardware video encoders, and running out fails the conversion. */
        private val converting = Mutex()

        /** Working copies of files being uploaded. In the cache, so Android may clear it; they are remade if so. */
        fun scratchRoot(context: Context) = File(context.cacheDir, "uploads")
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Decodes at roughly [maxSide] without loading the full image: the decoder skips pixels in
 * powers of two, then the result is scaled to size. Rotation from the camera is applied.
 */
private fun decodeScaled(source: ImageDecoder.Source, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        var sample = 1
        while (max(info.size.width, info.size.height) / (sample * 2) >= maxSide) sample *= 2
        decoder.setTargetSampleSize(sample)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compress() needs pixels in ordinary memory
    }.scaledTo(maxSide)

private fun Bitmap.scaledTo(maxSide: Int): Bitmap {
    val factor = maxSide.toDouble() / max(width, height)
    if (factor >= 1) return this
    return scale(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
}

// JPEGs made from a Bitmap carry no metadata, so display copies and thumbnails never include GPS.
private fun Bitmap.jpeg(file: File, quality: Int): File {
    file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, quality, it) }
    return file
}

/**
 * What the gallery needs to group bursts: a fingerprint of how the picture looks, and how
 * sharp it is. Both are measured on small grayscale copies, so they take milliseconds.
 */
private fun fingerprint(picture: Bitmap): Pair<Long, Double> {
    fun Bitmap.gray(): IntArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        // Perceived brightness: green counts most, blue least.
        return IntArray(pixels.size) { i -> val c = pixels[i]; (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000 }
    }
    val small = picture.scaledTo(THUMB_PX)
    return differenceHash(picture.scale(9, 8).gray()) to sharpness(small.gray(), small.width, small.height)
}

/** A message for the user when retrying can't help, or null when it might. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class]) // ExportException is part of the "unstable" converter API
private fun Exception.permanentProblem(): String? = when (this) {
    is Unusable -> message
    is FileNotFoundException, is SecurityException -> "It's no longer on this phone, or Postmarked lost access to it."
    is ImageDecoder.DecodeException -> "This photo's file couldn't be read."
    is ExportException -> "This video couldn't be converted."
    is StorageRestException if code == "EntityTooLarge" -> "Too big for the storage plan's upload limit."
    is RestException -> when (statusCode) {
        413 -> "Too big for the storage plan's upload limit."
        403 -> "You can no longer add to this trip."
        401, 408, 429 -> null // signed-in session expired, timeout, rate limit: all pass
        in 400..499 -> userMessage()
        else -> null // server trouble
    }
    else -> null // network trouble
}
