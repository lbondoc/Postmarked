package io.github.lbondoc.postmarked

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import io.github.lbondoc.postmarked.ui.ExportedImage
import io.github.lbondoc.postmarked.ui.Exporter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val QUALITY = 92 // Instagram recompresses anyway; this keeps detail without huge files

class AndroidExporter(private val activity: ComponentActivity) : Exporter {

    override fun share(images: List<ExportedImage>, text: String) {
        val uris = writeToCache(images)
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_TEXT, text)
            // Lets the receiving app read the files; ClipData carries the permission to every one of them.
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, null))
    }

    /** Into Pictures/Postmarked. Android 10+ lets an app add its own pictures without asking for storage access. */
    override suspend fun save(images: List<ExportedImage>) = withContext(Dispatchers.IO) {
        val resolver = activity.contentResolver
        for (exported in images) {
            val details = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, exported.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Postmarked")
                put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from the gallery until fully written
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, details) ?: throw IOException("Couldn't create ${exported.name}")
            try {
                resolver.openOutputStream(uri)?.use { exported.image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                    ?: throw IOException("Couldn't write ${exported.name}")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null) // don't leave a broken half-saved picture in the gallery
                throw e
            }
        }
    }

    override fun copy(text: String) {
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Caption", text))
    }

    /** Replaces the previous export's files: only the latest share is ever kept. */
    private fun writeToCache(images: List<ExportedImage>): List<Uri> {
        val dir = File(activity.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        return images.map { exported ->
            val file = File(dir, exported.name)
            file.outputStream().use { exported.image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        }
    }
}
