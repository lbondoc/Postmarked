package io.github.lbondoc.postmarked

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.PhotoSaver
import io.github.lbondoc.postmarked.data.SavedFile
import io.github.lbondoc.postmarked.data.extensionFor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Saves trip files into Pictures/Postmarked and shares them through the share sheet. */
class AndroidPhotoSaver(private val activity: ComponentActivity) : PhotoSaver {

    /** Android 10+ lets an app add its own pictures and videos without asking for storage access. */
    override suspend fun save(file: SavedFile): Unit = withContext(Dispatchers.IO) {
        download(file) { type, input ->
            val collection = if (file.kind == MediaKind.Video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val details = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name + extensionFor(type))
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Postmarked")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from the gallery until fully written
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(collection, details) ?: throw IOException("Couldn't create ${file.name}")
            try {
                resolver.openOutputStream(uri)?.use { copy(input, it) } ?: throw IOException("Couldn't write ${file.name}")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Unit
            } catch (e: Throwable) {
                resolver.delete(uri, null, null) // a cancelled or failed save leaves nothing half-written behind
                throw e
            }
        }
    }

    override suspend fun share(files: List<SavedFile>) {
        if (files.isEmpty()) return
        val shared = withContext(Dispatchers.IO) {
            // In the folder FileProvider shares from; replaced each time, so only the latest share is kept.
            val dir = File(activity.cacheDir, "exports/shared").apply { deleteRecursively(); mkdirs() }
            files.map { file ->
                download(file) { type, input ->
                    val out = File(dir, file.name + extensionFor(type))
                    out.outputStream().use { copy(input, it) }
                    out to type
                }
            }
        }
        val uris = shared.map { (file, _) -> FileProvider.getUriForFile(activity, "${activity.packageName}.files", file) }
        val types = shared.map { it.second }.distinct()
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
            // Lets the receiving app read the files; ClipData carries the permission to every one of them.
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, null))
    }

    /** Streams [file]'s signed link, so a 50 MB video never sits in memory whole. */
    private suspend fun <T> download(file: SavedFile, use: suspend (type: String, InputStream) -> T): T {
        val connection = URL(file.url).openConnection() as HttpURLConnection
        try {
            if (connection.responseCode !in 200..299) throw IOException("Download failed (${connection.responseCode})")
            val type = connection.contentType?.substringBefore(';') ?: if (file.kind == MediaKind.Video) "video/mp4" else "image/jpeg"
            return connection.inputStream.use { use(type, it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Copies in chunks, stopping promptly when the save is cancelled. */
    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }
}
