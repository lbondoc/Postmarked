package io.github.lbondoc.postmarked.web

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.storage
import io.github.lbondoc.postmarked.data.CameraRoll
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.MediaKind.Companion.DISPLAY
import io.github.lbondoc.postmarked.data.MediaKind.Companion.POSTER
import io.github.lbondoc.postmarked.data.MediaKind.Companion.THUMB
import io.github.lbondoc.postmarked.data.MediaKind.Companion.VIDEO
import io.github.lbondoc.postmarked.data.QueuedUpload
import io.github.lbondoc.postmarked.data.ScanResult
import io.github.lbondoc.postmarked.data.TOO_BIG
import io.github.lbondoc.postmarked.data.cancelUploads
import io.github.lbondoc.postmarked.data.pickedFileProblem
import io.github.lbondoc.postmarked.data.pickedKind
import io.github.lbondoc.postmarked.data.pickedMedia
import io.github.lbondoc.postmarked.data.supabase
import io.github.lbondoc.postmarked.data.userMessage
import io.github.lbondoc.postmarked.domain.color.palette
import io.github.lbondoc.postmarked.domain.media.ExifInfo
import io.github.lbondoc.postmarked.domain.media.differenceHash
import io.github.lbondoc.postmarked.domain.media.readExif
import io.github.lbondoc.postmarked.domain.media.sharpness
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

private const val EXIF_BYTES = 128 * 1024 // EXIF sits at the start of a JPEG; this is far more than it needs
private const val DISPLAY_PX = 2048
private const val THUMB_PX = 400
private const val PREVIEW_PX = 192 // the import grid's cells are 96 dp
private const val JPEG = "image/jpeg"
private const val PARALLEL_UPLOADS = 3 // as on the phone: requests wait on the network, three fill the gaps
private const val MAX_ATTEMPTS = 4 // the tab is open and someone is watching, so give up sooner than the phone

/** An upload Storage refused, with its HTTP status (0 when the network failed). */
private class UploadFailed(val status: Int) : Exception("Upload failed ($status)")

/**
 * The website's camera roll: a file picker instead of the phone's photos, and uploads that run in this tab
 * while it is open (no background upload on the web, SPEC §1.2). Videos go up as they are, since the browser
 * can't convert them. The files stay on the JavaScript side as File handles, so a 50 MB video is never copied.
 */
internal object WebCameraRoll : CameraRoll {
    private class Picked(val file: JsAny, val problem: String?, val preview: ByteArray?)

    private val picked = mutableMapOf<String, Picked>()
    private var nextId = 0
    private val scope = CoroutineScope(SupervisorJob())
    private var running: Job? = null
    private val claimed = mutableSetOf<String>()
    /** The upload running for each item, so cancelling stops it. */
    private val jobs = mutableMapOf<String, Job>()

    private val queue = MutableStateFlow<List<QueuedUpload>>(emptyList())
    override val uploads: StateFlow<List<QueuedUpload>> = queue.asStateFlow()
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Float>> = progress.asStateFlow()

    // A browser can't tell Wi-Fi from mobile data, so there is nothing to switch.
    override val mobileData: StateFlow<Boolean> = MutableStateFlow(true)
    override fun allowMobileData(allowed: Boolean) = Unit

    override val pickedByHand = true // the files the person chose in the picker

    override suspend fun requestAccess() = true // choosing files in the picker is the permission

    // The picker offers everything; the import screen then sorts what was chosen by the trip's dates and places.
    override suspend fun scan(fromMs: Long, untilMs: Long): ScanResult {
        val files = pickFiles().await<JsArray<JsAny>>()
        val items = (0 until files.length).mapNotNull { i -> files[i]?.let { read(it) } }
        return ScanResult(items, locationAllowed = true)
    }

    override suspend fun pickAny(): List<DeviceMedia> = scan(0, 0).items // the same file picker, any dates

    override fun preview(item: DeviceMedia): Any? = picked[item.uri]?.preview

    private suspend fun read(file: JsAny): DeviceMedia? {
        val name = fileName(file)
        val type = fileType(file)
        val kind = pickedKind(name, type) ?: return null
        val problem = pickedFileProblem(name, type, blobSize(file).toLong())
        val exif = if (kind == MediaKind.Photo && problem == null) {
            runCatching { readExif(bytesOf(fileStart(file, EXIF_BYTES).await())) }.getOrDefault(ExifInfo())
        } else {
            ExifInfo()
        }
        // Pictures the browser can't draw (HEIC) still show up, as an empty tile, and are refused when queued.
        val preview = if (problem == null) runCatching { previewJpeg(file, kind == MediaKind.Video) }.getOrNull() else null
        val uri = "picked:${nextId++}/$name"
        picked[uri] = Picked(file, problem, preview)
        return pickedMedia(uri, kind, exif, fileModified(file).toLong(), TimeZone.currentSystemDefault())
    }

    private suspend fun previewJpeg(file: JsAny, isVideo: Boolean): ByteArray {
        val canvas = scaled(picture(file, isVideo).await(), PREVIEW_PX, 0, 0)
        return bytesOf(blobBytes(jpeg(canvas, 0.7).await()).await())
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun upload(tripId: String, items: List<DeviceMedia>) {
        val now = Clock.System.now().toEpochMilliseconds()
        queue.update { queued ->
            queued + items.map { item ->
                // HEIC and files over the size limit go in with their reason, so the trip page says why.
                val file = picked[item.uri]
                val problem = if (file == null) "This file is no longer open here. Pick it again." else file.problem
                QueuedUpload(Uuid.random().toString(), tripId, item, queuedAtMs = now, problem = problem)
            }
        }
        if (running?.isActive != true) running = scope.launch { work() }
    }

    private suspend fun work() {
        warnBeforeLeaving(true)
        try {
            coroutineScope {
                repeat(PARALLEL_UPLOADS) {
                    launch {
                        while (true) {
                            // The browser runs this on one thread, so claiming needs no lock.
                            val next = queue.value.firstOrNull { it.waiting && it.mediaId !in claimed } ?: break
                            claimed += next.mediaId
                            val job = launch { uploadOrRecordProblem(next) }
                            jobs[next.mediaId] = job
                            job.join()
                            jobs.remove(next.mediaId)
                        }
                    }
                }
            }
        } finally {
            warnBeforeLeaving(false)
        }
    }

    private suspend fun uploadOrRecordProblem(start: QueuedUpload) {
        var attempt = 0
        while (true) {
            try {
                upload(start)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = e.permanentProblem()
                attempt++
                if (problem != null || attempt >= MAX_ATTEMPTS) {
                    save(current(start).copy(problem = problem ?: "Kept failing: ${e.userMessage()}"))
                    return
                }
                delay(5_000L * attempt) // a dropped connection or a server hiccup: wait, then carry on
            } finally {
                progress.update { it - start.mediaId }
            }
        }
    }

    override fun cancel(mediaIds: Set<String>) {
        val (kept, cancelled) = cancelUploads(queue.value, mediaIds)
        queue.value = kept
        progress.update { it - mediaIds }
        scope.launch {
            for (upload in cancelled) {
                jobs[upload.mediaId]?.let { it.cancel(); it.join() } // aborts its request (see upload's AbortController)
                runCatching { Media.discardUpload(upload) } // whatever it sent, including a file that just finished
            }
        }
    }

    private fun current(start: QueuedUpload) = queue.value.firstOrNull { it.mediaId == start.mediaId } ?: start

    private fun save(entry: QueuedUpload) = queue.update { list -> list.map { if (it.mediaId == entry.mediaId) entry else it } }

    private suspend fun upload(start: QueuedUpload) {
        val file = picked.getValue(start.item.uri).file
        val sha256 = sha256Hex(file).await<JsString>().toString()
        if (Media.exists(start.tripId, sha256)) return save(current(start).copy(finished = true))

        val isVideo = start.item.kind == MediaKind.Video
        val picture = picture(file, isVideo).await<JsAny>()
        val display = scaled(picture, DISPLAY_PX, 0, 0)
        val thumb = scaled(picture, THUMB_PX, 0, 0)
        // A photo's original isn't uploaded (it would use most of the free plan's 1 GB); a video is the file itself.
        val files: List<Pair<String, suspend () -> Pair<JsAny, String>>> = listOfNotNull(
            if (isVideo) VIDEO to { file to fileType(file).ifEmpty { "video/mp4" } } else null,
            (if (isVideo) POSTER else DISPLAY) to { jpeg(display, 0.85).await<JsAny>() to JPEG },
            THUMB to { jpeg(thumb, 0.8).await<JsAny>() to JPEG },
        )
        var entry = current(start)
        files.forEachIndexed { index, (name, produce) ->
            if (name in entry.uploadedFiles) return@forEachIndexed
            val (body, type) = produce()
            val token = supabase.auth.currentAccessTokenOrNull() ?: throw UploadFailed(401)
            val url = supabase.storage.resolveUrl("object/media/${Media.path(entry.tripId, entry.mediaId, name)}")
            val controller = abortController()
            val status = try {
                upload(url, body, type, token, supabase.supabaseKey, { sent ->
                    // Each file is an equal share of the item's bar, as on the phone.
                    if (queue.value.any { it.mediaId == start.mediaId }) progress.update { it + (start.mediaId to ((index + sent.toFloat()) / files.size)) }
                }, controller).await<JsNumber>().toInt()
            } catch (e: CancellationException) {
                abortNow(controller) // cancelled: stop sending
                throw e
            }
            if (status !in 200..299) throw UploadFailed(status)
            entry = entry.copy(uploadedFiles = entry.uploadedFiles + name)
            save(entry)
        }
        // Only photos are grouped into bursts, so only they need a fingerprint and sharpness, measured as on the phone.
        val dhash = if (isVideo) null else differenceHash(gray(scaled(picture, 0, 9, 8)))
        val sharp = if (isVideo) null else sharpness(gray(thumb), canvasWidth(thumb), canvasHeight(thumb))
        if (!Media.add(entry, sha256, dhash, sharp, palette(intsOf(argbPixels(thumb))))) {
            // A friend's copy of the same file got in while ours was uploading: drop ours.
            Media.deleteFiles(entry.item.kind.files.map { Media.path(entry.tripId, entry.mediaId, it) })
        }
        save(entry.copy(finished = true, attempts = 0))
    }

    /** Perceived brightness of each pixel: green counts most, blue least (the phone's formula). */
    private fun gray(canvas: JsAny): IntArray = intsOf(argbPixels(canvas)).let { px ->
        IntArray(px.size) { i ->
            val c = px[i]
            (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
        }
    }

    /** A message for the user when retrying can't help, or null when it might. */
    private fun Exception.permanentProblem(): String? = when (this) {
        is UploadFailed -> when (status) {
            413 -> TOO_BIG
            403 -> "You can no longer add to this trip."
            0, 401, 408, 429 -> null // offline, session expired, timeout, rate limit: all pass
            in 400..499 -> "Storage refused this file ($status)."
            else -> null
        }
        is RestException -> when (statusCode) {
            403 -> "You can no longer add to this trip."
            401, 408, 429 -> null
            in 400..499 -> userMessage()
            else -> null
        }
        else -> null
    }
}
