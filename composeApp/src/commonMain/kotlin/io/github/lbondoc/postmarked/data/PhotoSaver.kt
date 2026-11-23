package io.github.lbondoc.postmarked.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A trip photo's original file or a video's `video.mp4`, as a signed link, ready to save or share. */
class SavedFile(val url: String, val name: String, val kind: MediaKind)

/** What only the platform can do with a trip's own files (SPEC §3.2). */
interface PhotoSaver {
    /** Saves to the device: Pictures/Postmarked on Android, a download on the web. */
    suspend fun save(file: SavedFile)

    /** Hands one photo or video to the platform's sharing: the share sheet, or Web Share (else a download). */
    suspend fun share(file: SavedFile)
}

/** How far saving several has got: [done] of [total] attempted, [failed] of those didn't save. */
data class SaveProgress(val done: Int, val total: Int, val failed: Int = 0) {
    val finished: Boolean get() = done == total
}

/**
 * Saves [items] one after another, reporting after each. One that fails is counted and the rest carry on, so a
 * single missing file doesn't stop a big save. Cancelling the calling coroutine stops before the next item.
 */
suspend fun <T> saveEach(items: List<T>, save: suspend (T) -> Unit, onProgress: (SaveProgress) -> Unit): SaveProgress {
    var progress = SaveProgress(0, items.size)
    onProgress(progress)
    for (item in items) {
        coroutineContext.ensureActive()
        val ok = try {
            save(item)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        progress = progress.copy(done = progress.done + 1, failed = progress.failed + if (ok) 0 else 1)
        onProgress(progress)
    }
    return progress
}

/** A file name extension for a download's type, so the saved file opens in the right app. */
fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
    "image/png" -> ".png"
    "image/heic", "image/heif" -> ".heic"
    "image/webp" -> ".webp"
    "video/mp4" -> ".mp4"
    "video/quicktime" -> ".mov"
    "video/webm" -> ".webm"
    else -> if (mimeType.startsWith("video/")) ".mp4" else ".jpg"
}
