package io.github.lbondoc.postmarked.web

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.lbondoc.postmarked.ui.ExportedImage
import io.github.lbondoc.postmarked.ui.Exporter
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

private fun jsShare(text: String): Unit = js(
    "navigator.share ? navigator.share({ text: text }).catch(() => {}) : navigator.clipboard.writeText(text)",
)

private fun jsOpen(url: String): Unit = js("window.open(url, '_blank', 'noopener')")

private fun copyText(text: String): Unit = js("navigator.clipboard.writeText(text).catch(() => {})")

/** Web Share where the browser has it (phones), else the text goes to the clipboard. */
internal fun shareText(text: String) = jsShare(text)

/** Compose draws on a canvas, so a video opens in its own tab, where the browser plays it. */
@Composable
internal fun WebVideo(url: String, modifier: Modifier) = Box(modifier, Alignment.Center) {
    TextButton(onClick = { jsOpen(url) }) { Text("Play video") }
}

private const val JPEG_QUALITY = 92 // as on the phone: Instagram recompresses anyway

private fun ExportedImage.jpegBytes(): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)!!.bytes

private fun ExportedImage.fileName() = if (name.endsWith(".jpg")) name else "$name.jpg"

/** Exports on the web: Web Share with the pictures where the browser has it (phones), else they download. */
internal object WebExporter : Exporter {
    override fun share(images: List<ExportedImage>, text: String) {
        val files = images.map { namedBytes(it.fileName(), it.jpegBytes().toUint8Array()) }.toJsArray()
        if (!shareFiles(files, text)) {
            images.forEach(::downloadImage)
            copy(text) // the caption, ready to paste
        }
    }

    override fun postToInstagram(image: ExportedImage, text: String) = share(listOf(image), text)

    override fun openInstagram() = false // a web page can't open the Instagram app

    override suspend fun save(images: List<ExportedImage>) = images.forEach(::downloadImage)

    override fun copy(text: String) = copyText(text)

    private fun downloadImage(image: ExportedImage) = download(image.jpegBytes().toUint8Array(), image.fileName(), "image/jpeg")
}
