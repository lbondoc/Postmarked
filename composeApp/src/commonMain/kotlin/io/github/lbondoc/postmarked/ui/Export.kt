package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.domain.summary.arrange
import io.github.lbondoc.postmarked.domain.summary.panoramaSlices
import io.github.lbondoc.postmarked.domain.summary.noteToQuote
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.data.AlbumLook
import io.github.lbondoc.postmarked.data.Media
import io.github.lbondoc.postmarked.data.Song
import io.github.lbondoc.postmarked.data.Summary
import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.userMessage
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.colourName
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.color.readableOn
import io.github.lbondoc.postmarked.domain.summary.Box
import io.github.lbondoc.postmarked.domain.summary.Frame
import io.github.lbondoc.postmarked.domain.summary.Layout
import io.github.lbondoc.postmarked.domain.summary.SongCredit
import io.github.lbondoc.postmarked.domain.summary.caption
import io.github.lbondoc.postmarked.data.Crop
import io.github.lbondoc.postmarked.data.tileAspect
import io.github.lbondoc.postmarked.domain.summary.cropRegion
import io.github.lbondoc.postmarked.domain.summary.fit
import io.github.lbondoc.postmarked.domain.summary.postingOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Instagram's portrait and square sizes, in pixels. */
enum class ExportSize(val label: String, val width: Int, val height: Int) {
    Portrait("Portrait 4:5", 1080, 1350),
    Tall("Tall 3:4", 1080, 1440),
    Square("Square", 1080, 1080),
}

enum class ExportKind(val label: String) {
    Single("One post"),
    Carousel("Carousel"),
    Grid("Profile grid (9 posts)"),
}

class ExportedImage(val name: String, val image: ImageBitmap)

/** What only the platform can do with finished images. */
interface Exporter {
    /** Opens the share sheet (messages, email, ...) with [images] and [text]. */
    fun share(images: List<ExportedImage>, text: String)

    /**
     * Hands one picture straight to Instagram, which then asks Feed, Story or Message; the share
     * sheet if Instagram isn't installed. One at a time because Instagram doesn't reliably take
     * several pictures handed to it at once.
     */
    fun postToInstagram(image: ExportedImage, text: String)

    /** Opens Instagram; false if it isn't installed. */
    fun openInstagram(): Boolean

    /** Saves [images] to the phone's photos, in a Postmarked album. */
    suspend fun save(images: List<ExportedImage>)

    fun copy(text: String)
}

/** Everything needed to draw a summary, with pictures already downloaded. */
private class Drawing(
    val summary: Summary,
    val layout: Layout,
    val look: AlbumLook,
    val pictures: Map<String, ImageBitmap>,
    val grades: (SummaryTile) -> FloatArray?,
    val textColours: List<Int>,
    val measurer: TextMeasurer,
    val font: FontFamily,
)

private val ONE = Density(1f) // draw in plain pixels: 1 dp = 1 sp = 1 px

private fun Int.color() = rgbColor(this)

/**
 * Draws the summary as [kind] at [size]. Pictures are the full display copies; the result is
 * fresh pixels, so it carries no GPS or other metadata from the originals.
 */
private suspend fun renderExport(material: SummaryMaterial, d: Drawing, kind: ExportKind, size: ExportSize): List<ExportedImage> =
    withContext(Dispatchers.Default) {
        val base = d.summary.name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').ifEmpty { "postmarked" }
        fun image(draw: DrawScope.() -> Unit): ImageBitmap {
            val bitmap = ImageBitmap(size.width, size.height)
            CanvasDrawScope().draw(ONE, LayoutDirection.Ltr, Canvas(bitmap), Size(size.width.toFloat(), size.height.toFloat())) { draw() }
            return bitmap
        }
        val solo = d.summary.tiles.filter { it.media != null || it.bg != null }
        when (kind) {
            ExportKind.Single -> listOf(ExportedImage("$base.jpg", image { whole(d) }))
            // A panorama: drawn once, as wide as all its slides together, then cut so the photo joins up when swiped.
            ExportKind.Carousel if d.layout.slides > 1 -> {
                val wide = ImageBitmap(size.width * d.layout.slides, size.height)
                CanvasDrawScope().draw(ONE, LayoutDirection.Ltr, Canvas(wide), Size(wide.width.toFloat(), wide.height.toFloat())) { whole(d) }
                panoramaSlices(d.layout.slides, wide.width.toDouble()).mapIndexed { i, (left, _) ->
                    ExportedImage("$base-${i + 1}.jpg", image { drawImage(wide, topLeft = Offset(-left.toFloat(), 0f)) })
                }
            }
            ExportKind.Carousel -> listOf(ExportedImage("$base-1.jpg", image { whole(d) })) +
                solo.mapIndexed { i, tile -> ExportedImage("$base-${i + 2}.jpg", image { alone(d, tile) }) }
            // Numbered in posting order: post 1 first, so it ends up bottom-right of the profile.
            ExportKind.Grid -> postingOrder(d.summary.tiles.size).mapIndexed { n, index ->
                ExportedImage("$base-post-${n + 1}-of-${d.summary.tiles.size}.jpg", image { alone(d, d.summary.tiles[index]) })
            }
        }
    }

/** The whole summary on its theme's paper, with the theme's border, edge and postmark. */
private fun DrawScope.whole(d: Drawing) {
    val c = d.look.colours
    val theme = d.look.theme
    val w = size.width.toDouble()
    val borderColour = (if (theme.exportBorderInk) c.ink else c.paper).color()
    drawRect(borderColour)
    val border = theme.exportBorder * w
    // Adaptive layouts follow each photo's own shape, which the downloaded pictures tell exactly.
    val arranged = d.layout.arrange(List(d.layout.slots.size) { i ->
        d.summary.tiles.getOrNull(i)?.let { t -> t.media?.let { tileAspect(t, d.pictures[it]?.let { p -> p.width.toDouble() / p.height }) } }
    })
    val canvas = fit(arranged.aspect, Box(border, border, w - 2 * border, size.height - 2 * border))
    drawRect(c.paper.color(), Offset(canvas.left.toFloat(), canvas.top.toFloat()), Size(canvas.width.toFloat(), canvas.height.toFloat()))

    arranged.slots.forEachIndexed { i, slot ->
        val tile = d.summary.tiles.getOrElse(i) { SummaryTile() }
        val b = Box(canvas.left + slot.x * canvas.width, canvas.top + slot.y * canvas.height, slot.w * canvas.width, slot.h * canvas.height)
        rotate(slot.rotation.toFloat(), Offset((b.left + b.width / 2).toFloat(), (b.top + b.height / 2).toFloat())) {
            framed(slot.frame, b) { inner -> tileContent(d, tile, inner, textScale = 1.0) }
        }
    }

    when (theme.exportEdge) {
        // Deckled: little bites out of the picture's edge, like an old photo's cut paper.
        "deckled" -> {
            val r = (w * 0.006).toFloat()
            var x = canvas.left.toFloat()
            while (x <= canvas.left + canvas.width) {
                drawCircle(borderColour, r, Offset(x, canvas.top.toFloat()))
                drawCircle(borderColour, r, Offset(x, (canvas.top + canvas.height).toFloat()))
                x += r * 3
            }
            var y = canvas.top.toFloat()
            while (y <= canvas.top + canvas.height) {
                drawCircle(borderColour, r, Offset(canvas.left.toFloat(), y))
                drawCircle(borderColour, r, Offset((canvas.left + canvas.width).toFloat(), y))
                y += r * 3
            }
        }
        // Film: sprocket holes along the top and bottom borders.
        "film" -> if (border > 0) {
            val hole = Size((border * 0.55).toFloat(), (border * 0.4).toFloat())
            var x = (border * 0.4).toFloat()
            while (x + hole.width < size.width) {
                drawRect(c.paper.color(), Offset(x, ((border - hole.height) / 2).toFloat()), hole)
                drawRect(c.paper.color(), Offset(x, (size.height - (border + hole.height) / 2).toFloat()), hole)
                x += hole.width * 2f
            }
        }
    }

    if (theme.exportStamp) {
        // A postmark over the bottom-right corner, in the theme's second accent.
        val r = (w * 0.09).toFloat()
        val centre = Offset((canvas.left + canvas.width - r * 0.9).toFloat(), (canvas.top + canvas.height - r * 0.9).toFloat())
        val ink = c.accent2.color().copy(alpha = 0.85f)
        rotate(-12f, centre) {
            drawCircle(ink, r, centre, style = Stroke(r * 0.07f))
            drawCircle(ink, r * 0.82f, centre, style = Stroke(r * 0.03f))
            val label = d.measurer.measure(
                AnnotatedString("POSTMARKED"),
                TextStyle(color = ink, fontFamily = d.font, fontWeight = FontWeight.Bold, fontSize = (r * 0.3f).sp),
                density = ONE,
            )
            drawText(label, topLeft = Offset(centre.x - label.size.width / 2f, centre.y - label.size.height / 2f))
        }
    }
}

/** One tile on its own, filling the image: a carousel slide or one post of a profile grid. */
private fun DrawScope.alone(d: Drawing, tile: SummaryTile) {
    drawRect(d.look.colours.paper.color())
    tileContent(d, tile, Box(0.0, 0.0, size.width.toDouble(), size.height.toDouble()), textScale = 1.4)
}

private inline fun DrawScope.framed(frame: Frame, b: Box, content: (Box) -> Unit) {
    when (frame) {
        Frame.None -> content(b)
        Frame.Polaroid -> {
            drawRect(Color.White, Offset(b.left.toFloat(), b.top.toFloat()), Size(b.width.toFloat(), b.height.toFloat()))
            val side = b.width * 0.05
            content(Box(b.left + side, b.top + side, b.width - 2 * side, b.height - side - b.width * 0.18))
        }
        Frame.Film -> {
            drawRect(Color(0xFF151515), Offset(b.left.toFloat(), b.top.toFloat()), Size(b.width.toFloat(), b.height.toFloat()))
            val hole = Size((b.width * 0.05).toFloat(), (b.height * 0.06).toFloat())
            var y = b.top + b.height * 0.05
            while (y < b.top + b.height - hole.height) {
                drawRect(Color(0xFFEDE6D6), Offset((b.left + b.width * 0.02).toFloat(), y.toFloat()), hole)
                drawRect(Color(0xFFEDE6D6), Offset((b.left + b.width * 0.93).toFloat(), y.toFloat()), hole)
                y += b.height * 0.12
            }
            content(Box(b.left + b.width * 0.1, b.top + b.height * 0.03, b.width * 0.8, b.height * 0.94))
        }
    }
}

private fun DrawScope.tileContent(d: Drawing, tile: SummaryTile, b: Box, textScale: Double) {
    val topLeft = IntOffset(b.left.roundToInt(), b.top.roundToInt())
    val dstSize = IntSize(b.width.roundToInt(), b.height.roundToInt())
    val picture = tile.media?.let { d.pictures[it] }
    when {
        picture != null -> {
            val chosen = tile.crop ?: Crop()
            val crop = cropRegion(picture.width.toDouble(), picture.height.toDouble(), b.width / b.height, chosen.x, chosen.y, chosen.zoom)
            drawImage(
                picture,
                srcOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt()),
                srcSize = IntSize(crop.width.roundToInt(), crop.height.roundToInt()),
                dstOffset = topLeft,
                dstSize = dstSize,
                colorFilter = d.grades(tile)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            )
        }
        tile.bg != null -> {
            val bg = hexToRgb(tile.bg)
            drawRect(bg.color(), Offset(b.left.toFloat(), b.top.toFloat()), Size(b.width.toFloat(), b.height.toFloat()))
            tile.text?.let { text ->
                val fontSize = (b.width / 9 * textScale).coerceAtMost(b.height / 4)
                val layout = d.measurer.measure(
                    AnnotatedString(text),
                    TextStyle(
                        color = readableOn(bg, d.textColours).color(), fontFamily = d.font, fontWeight = FontWeight.Bold,
                        fontSize = fontSize.sp, lineHeight = (fontSize * 1.1).sp, textAlign = TextAlign.Center,
                    ),
                    constraints = Constraints(maxWidth = (b.width * 0.86).roundToInt()),
                    density = ONE,
                )
                drawText(layout, topLeft = Offset((b.left + (b.width - layout.size.width) / 2).toFloat(), (b.top + (b.height - layout.size.height) / 2).toFloat()))
            }
        }
        else -> drawRect(Color(0xFFE6E0D4), Offset(b.left.toFloat(), b.top.toFloat()), Size(b.width.toFloat(), b.height.toFloat()))
    }
}

/**
 * The export sheet: choose post, carousel or profile grid and a size, pick a song to credit,
 * edit the generated caption, then share or save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    material: SummaryMaterial,
    summary: Summary,
    layout: Layout,
    look: AlbumLook,
    songs: List<Song>,
    miles: Int?,
    stops: Int,
    exporter: Exporter,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val font = headingFont()
    var kind by remember { mutableStateOf(if (layout.slides > 1) ExportKind.Carousel else ExportKind.Single) }
    var size by remember { mutableStateOf(if (layout == Layout.Grid3x3) ExportSize.Square else ExportSize.Portrait) }
    var song by remember { mutableStateOf(songs.firstOrNull()) }
    fun generated() = caption(
        tripName = material.trip.name,
        start = material.trip.startDate,
        end = material.trip.endDate,
        miles = miles,
        stops = stops,
        places = material.trip.regions.map { it.label.substringBefore(',') },
        song = song?.let { SongCredit(it.title, it.artist) },
        colour = summary.colours.firstOrNull()?.takeIf { summary.mode == SummaryMode.Monochrome.name }?.let { colourName(Oklab.fromRgb(hexToRgb(it))) },
        // The one day note among the days this summary's photos are from, if there is one.
        note = noteToQuote(summary.tiles.mapNotNull { tile -> tile.media?.let { material.byId[it]?.takenOn } }.toSet(), material.notes),
    )
    var text by remember { mutableStateOf(generated()) }
    var working by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    // The finished pictures, kept so a grid can be posted one at a time, and Save after Share is instant.
    var made by remember(kind, size) { mutableStateOf<List<ExportedImage>?>(null) }
    var posted by remember(kind, size) { mutableIntStateOf(0) }

    fun export(action: suspend (List<ExportedImage>) -> String) = scope.launch {
        done = null
        made?.let { done = action(it); return@launch }
        working = "Making the images…"
        try {
            val needed = summary.tiles.mapNotNull { it.media }.distinct().mapNotNull { material.byId[it] }
            // Full-size pictures, four downloads at a time.
            val pictures = coroutineScope {
                needed.chunked(4).flatMap { chunk ->
                    chunk.map { item -> async { item.id to Media.pictureBytes(material.trip.id, item).decodeToImageBitmap() } }.awaitAll()
                }.toMap()
            }
            val drawing = Drawing(summary, layout, look, pictures, material.gradeFor(summary), material.textColours(summary), measurer, font)
            val images = renderExport(material, drawing, kind, size)
            made = images
            done = action(images)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            done = "Couldn't export: ${e.userMessage()}"
        } finally {
            working = null
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Export for Instagram", style = MaterialTheme.typography.titleLarge)
            ChipRow(ExportKind.entries.filter { it != ExportKind.Grid || layout == Layout.Grid3x3 }, kind, { it.label }) { kind = it }
            when (kind) {
                ExportKind.Single -> Text("The whole summary as one picture.", style = MaterialTheme.typography.bodySmall)
                ExportKind.Carousel -> Text(
                    if (layout.slides > 1) "The panorama cut into ${layout.slides} slides that join up when swiped."
                    else "The summary first, then each tile as its own slide.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ExportKind.Grid -> Text(
                    "Nine posts that line up on your profile. Post them in order, starting with post 1: Instagram puts the newest at the top left.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ChipRow(ExportSize.entries, size, { it.label }) { size = it }
            if (songs.isNotEmpty()) {
                Text("Song to credit (add the music itself in Instagram)", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf<Song?>(null) + songs, song, { it?.title ?: "No song" }) { song = it; text = generated() }
            }
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            OutlinedButton(onClick = { exporter.copy(text); done = "Caption copied." }) { Text("Copy caption") }
            working?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.padding(2.dp)); Text(it) } }
            done?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val idle = working == null
            when (kind) {
                ExportKind.Single -> InkButton("Post to Instagram", enabled = idle, modifier = Modifier.fillMaxWidth(), onClick = {
                    export { images ->
                        exporter.copy(text)
                        exporter.postToInstagram(images.single(), text)
                        "Opened Instagram with the picture. The caption is copied, ready to paste."
                    }
                })
                // Saved, then picked in Instagram: handing it several pictures at once often drops all but one.
                ExportKind.Carousel -> InkButton("Post to Instagram", enabled = idle, modifier = Modifier.fillMaxWidth(), onClick = {
                    export { images ->
                        exporter.save(images)
                        exporter.copy(text)
                        "Saved the ${images.size} pictures to your photos and copied the caption. " +
                            if (exporter.openInstagram()) "In Instagram, start a new post, tap Select multiple, and pick the ${images.size} newest, summary first."
                            else "Instagram isn't installed on this phone."
                    }
                })
                ExportKind.Grid -> {
                    val total = summary.tiles.size
                    if (posted < total) InkButton("Post ${posted + 1} of $total to Instagram", enabled = idle, modifier = Modifier.fillMaxWidth(), onClick = {
                        export { images ->
                            exporter.copy(text)
                            exporter.postToInstagram(images[posted], text)
                            posted++
                            if (posted < images.size) "Post $posted is open in Instagram, with the caption copied. Come back here for post ${posted + 1}."
                            else "That was the last one: all ${images.size} posts are done."
                        }
                    }) else TextButton(onClick = { posted = 0 }) { Text("All $total posted · start again") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export { images -> exporter.copy(text); exporter.share(images, text); "Opened the share sheet. The caption is copied, ready to paste." } }, enabled = idle) {
                    Text("Share elsewhere…")
                }
                OutlinedButton(onClick = { export { images -> exporter.save(images); "Saved ${images.size} to your photos, in the Postmarked album." } }, enabled = idle) {
                    Text("Save to photos")
                }
            }
            Text("Exported pictures carry no location or other details from the originals.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) FilterChip(selected = option == chosen, onClick = { onChoose(option) }, label = { Text(label(option)) })
    }
}
