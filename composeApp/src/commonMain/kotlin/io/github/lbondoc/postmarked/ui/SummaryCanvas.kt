package io.github.lbondoc.postmarked.ui

import androidx.compose.runtime.produceState
import coil3.request.SuccessResult
import coil3.SingletonImageLoader
import io.github.lbondoc.postmarked.domain.summary.Slot
import io.github.lbondoc.postmarked.domain.summary.arrange
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.translate
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import io.github.lbondoc.postmarked.data.Crop
import io.github.lbondoc.postmarked.domain.summary.Box as DomainBox
import io.github.lbondoc.postmarked.domain.summary.cropRegion
import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.color.readableOn
import io.github.lbondoc.postmarked.domain.summary.Frame
import io.github.lbondoc.postmarked.domain.summary.Layout
import kotlin.math.roundToInt

private val PAPER = Color(0xFFF7F3EA)

fun rgbColor(rgb: Int) = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))

/**
 * Draws a summary: [tiles] placed in [layout]'s slots on a paper-white canvas. [gradeFor] gives
 * a tile's colour matrix (4 x 5, offsets 0..255), or null to show it untouched.
 *
 * Touch, when the callbacks are given: a tap opens a tile ([onTap]). Holding a tile selects it,
 * and dragging on selects every tile in between, in slot order ([onSelect]); while some are
 * selected, a tap adds or removes one. Holding a selected tile and dragging picks the selection up
 * as a stack: dashed, numbered outlines show where each will land, letting go drops them there
 * ([onMove]), and letting go outside the summary puts everything back.
 */
@Composable
fun SummaryCanvas(
    layout: Layout,
    tiles: List<SummaryTile>,
    media: Map<String, TripMedia>,
    pictures: Map<String, String>,
    gradeFor: (SummaryTile) -> FloatArray?,
    textColours: List<Int>,
    modifier: Modifier = Modifier,
    paper: Color = PAPER,
    selected: Set<Int> = emptySet(),
    onSelect: ((Set<Int>) -> Unit)? = null,
    onMove: ((Set<Int>, Int) -> Unit)? = null,
    onTap: ((Int) -> Unit)? = null,
) {
    // Adaptive layouts follow each photo's shape (SPEC §8.4): measured as the pictures load, then reflowed.
    val aspects = rememberPictureAspects(tiles, pictures)
    val arranged = remember(layout, aspects, tiles.size) { layout.arrange(List(layout.slots.size) { i -> tiles.getOrNull(i)?.media?.let { aspects[it] } }) }
    val slots = arranged.slots
    // The gesture below runs for as long as a finger is down; these let it see the latest values.
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val chosen by rememberUpdatedState(selected)
    var moving by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    // On the canvas itself, so it sees every touch whichever tile is under the finger.
    val touch = if (onTap == null && onSelect == null) Modifier else Modifier.pointerInput(slots) {
        fun slotAt(point: Offset) = slotAt(slots, point, size.width.toFloat(), size.height.toFloat())
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = slotAt(down.position) ?: return@awaitEachGesture
            val held = awaitLongPressOrCancellation(down.id)
            if (held == null) {
                // Lifted before the long-press time: a tap, unless the finger slid (that was a scroll).
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (up != null && !up.pressed && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                    val now = chosen
                    val pick = select
                    if (now.isNotEmpty() && pick != null) pick(if (start in now) now - start else now + start) else tap?.invoke(start)
                }
                return@awaitEachGesture
            }
            val before = chosen
            if (start in before && move != null) {
                moving = true
                finger = held.position
                val lifted = drag(held.id) { change -> finger = change.position; change.consume() }
                val target = slotAt(finger)
                moving = false
                if (lifted && target != null) move?.invoke(before, target)
            } else {
                select?.invoke(before + start)
                drag(held.id) { change ->
                    change.consume()
                    slotAt(change.position)?.let { at -> select?.invoke(before + (minOf(start, at)..maxOf(start, at))) }
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(arranged.aspect.toFloat()).background(paper).then(touch)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val hover = if (moving) slotAt(slots, finger, width, height) else null
        val all = List(slots.size) { tiles.getOrNull(it) ?: SummaryTile() }
        // While moving, the summary shows where everything would end up.
        val (shown, landing) = if (hover != null) moveTiles(all, selected, hover) else all to IntRange.EMPTY
        val selecting = selected.isNotEmpty() && onSelect != null

        slots.forEachIndexed { i, slot ->
            val tile = shown[i]
            // Layouts are fractions of the canvas; these are pixels.
            val left = (slot.x * width).toFloat()
            val top = (slot.y * height).toFloat()
            val w = (slot.w * width).toFloat()
            val h = (slot.h * height).toFloat()
            val landed = i in landing
            val on = i in selected
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .graphicsLayer { rotationZ = slot.rotation.toFloat() }
                    .semantics {
                        contentDescription = describe(tile, media) + if (on) ", selected" else ""
                        // The same choices for TalkBack, which can't hold and drag.
                        if (selecting) onClick(if (on) "Unselect" else "Select") { onSelect?.invoke(if (on) selected - i else selected + i); true }
                        else if (onTap != null) onClick("Change this tile") { onTap(i); true }
                        if (onSelect != null && !selecting) onLongClick("Select") { onSelect(setOf(i)); true }
                    },
            ) {
                Box(Modifier.fillMaxSize().alpha(if (landed || (moving && hover == null && on)) 0.35f else 1f)) {
                    Framed(slot.frame) { TileContent(tile, media, pictures, gradeFor, textColours, w, density.density) }
                }
                if (landed) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        val stroke = 3.dp.toPx()
                        drawRect(SELECTED, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 3, stroke * 2))))
                    })
                    Text(
                        "${i - landing.first + 1}",
                        Modifier.align(Alignment.Center).background(SELECTED, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                } else if (selecting && !moving) {
                    if (on) Box(Modifier.fillMaxSize().border(3.dp, SELECTED))
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                            .background(if (on) SELECTED else Color(0x40231F1C), CircleShape).border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (moving) Stack(selected.sorted().map { all[it] }, finger, media, pictures, gradeFor, textColours)
    }
}

/** Which slot is under [point] (in pixels on a [width] x [height] canvas), if any. */
private fun slotAt(slots: List<Slot>, point: Offset, width: Float, height: Float) = slots.indexOfFirst { s ->
    point.x in (s.x * width).toFloat()..((s.x + s.w) * width).toFloat() && point.y in (s.y * height).toFloat()..((s.y + s.h) * height).toFloat()
}.takeIf { it >= 0 }

/** The red of selection rings, ticks and landing outlines: the app's postal red. */
private val SELECTED = Color(0xFFB5452F)

/** The picked-up tiles folded into a little pile under the finger, with how many there are. */
@Composable
private fun Stack(
    tiles: List<SummaryTile>,
    at: Offset,
    media: Map<String, TripMedia>,
    pictures: Map<String, String>,
    gradeFor: (SummaryTile) -> FloatArray?,
    textColours: List<Int>,
) {
    val density = LocalDensity.current
    val side = 84.dp
    val half = with(density) { (side / 2).toPx() }
    // Up and to the left of the finger, so the finger doesn't hide it.
    Box(Modifier.offset { IntOffset((at.x - half * 1.6f).roundToInt(), (at.y - half * 1.8f).roundToInt()) }.zIndex(2f)) {
        tiles.take(3).reversed().forEachIndexed { layer, tile ->
            val depth = minOf(tiles.size, 3) - 1 - layer // 0 is the top card
            Box(
                Modifier.size(side).graphicsLayer { rotationZ = listOf(0f, 5f, -7f)[depth] }
                    .shadow(6.dp).background(Color.White).padding(4.dp),
            ) { TileContent(tile, media, pictures, gradeFor, textColours, with(density) { side.toPx() }, density.density) }
        }
        if (tiles.size > 1) Text(
            "${tiles.size}",
            Modifier.align(Alignment.TopEnd).offset(10.dp, (-10).dp).background(SELECTED, CircleShape).border(2.dp, Color.White, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White, fontWeight = FontWeight.Bold,
        )
    }
}

private fun describe(tile: SummaryTile, media: Map<String, TripMedia>): String = when {
    tile.media != null -> (media[tile.media]?.profile?.displayName?.let { "Photo by $it" } ?: "Photo")
    tile.text != null -> "Text: ${tile.text}"
    tile.bg != null -> "Colour tile"
    else -> "Empty tile"
}

@Composable
private fun Framed(frame: Frame, content: @Composable () -> Unit) = when (frame) {
    Frame.None -> Box(Modifier.fillMaxSize()) { content() }
    // White card with a deeper bottom edge, like an instant photo.
    Frame.Polaroid -> Box(
        Modifier.fillMaxSize().shadow(4.dp).background(Color.White).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 22.dp),
    ) { content() }
    // Black film with sprocket holes down both sides.
    Frame.Film -> Box(
        Modifier.fillMaxSize().background(Color(0xFF151515)).drawBehind {
            val hole = Size(size.width * 0.05f, size.height * 0.06f)
            var y = size.height * 0.05f
            while (y < size.height - hole.height) {
                drawRect(Color(0xFFEDE6D6), Offset(size.width * 0.02f, y), hole)
                drawRect(Color(0xFFEDE6D6), Offset(size.width * 0.93f, y), hole)
                y += size.height * 0.12f
            }
        }.padding(horizontal = 18.dp, vertical = 6.dp),
    ) { content() }
}

@Composable
private fun TileContent(
    tile: SummaryTile,
    media: Map<String, TripMedia>,
    pictures: Map<String, String>,
    gradeFor: (SummaryTile) -> FloatArray?,
    textColours: List<Int>,
    widthPx: Float,
    density: Float,
) {
    when {
        tile.media != null -> CroppedPhoto(
            pictures[tile.media], tile.crop,
            // The look is applied while drawing; the photo itself is never changed.
            gradeFor(tile)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            Modifier.fillMaxSize().background(Color(0xFFE6E0D4)),
        )
        tile.bg != null -> {
            val bg = hexToRgb(tile.bg)
            Box(Modifier.fillMaxSize().background(rgbColor(bg)).padding(8.dp), contentAlignment = Alignment.Center) {
                tile.text?.let {
                    Text(
                        it,
                        color = rgbColor(readableOn(bg, textColours)),
                        // Scales with the tile: about a ninth of its width.
                        fontSize = (widthPx / density / 9).sp,
                        lineHeight = (widthPx / density / 8).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                    )
                }
            }
        }
        else -> Box(Modifier.fillMaxSize().background(Color(0xFFE6E0D4)), contentAlignment = Alignment.Center) {
            Text("+", color = Color(0xFF8A8172), fontSize = 28.sp)
        }
    }
}

/**
 * A photo filling its box, showing the part [crop] chose (the middle when null), drawn through
 * [colorFilter]. With [onCrop], dragging moves the photo and pinching zooms, reporting each change.
 */
@Composable
fun CroppedPhoto(url: String?, crop: Crop?, colorFilter: ColorFilter?, modifier: Modifier = Modifier, onCrop: ((Crop) -> Unit)? = null) {
    // Drawn by hand rather than by AsyncImage, so Coil can't size it from the layout: cap it here.
    val context = LocalPlatformContext.current
    val painter = rememberAsyncImagePainter(remember(url) { ImageRequest.Builder(context).data(url).size(PHOTO_PIXELS).build() })
    val current by rememberUpdatedState(crop ?: Crop())
    val report by rememberUpdatedState(onCrop)
    fun region(frame: Size, c: Crop): DomainBox? {
        val src = painter.intrinsicSize
        if (src.isUnspecified || src.width <= 0f || frame.width <= 0f) return null
        return cropRegion(src.width.toDouble(), src.height.toDouble(), (frame.width / frame.height).toDouble(), c.x, c.y, c.zoom)
    }
    Canvas(
        modifier.clipToBounds().then(
            if (onCrop == null) Modifier else Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val frame = Size(size.width.toFloat(), size.height.toFloat())
                    val before = region(frame, current) ?: return@detectTransformGestures
                    val src = painter.intrinsicSize
                    // Screen pixels per photo pixel; dragging right shows more of the left.
                    val scale = frame.width / before.width
                    val moved = Crop(
                        x = current.x - pan.x / scale / src.width,
                        y = current.y - pan.y / scale / src.height,
                        zoom = (current.zoom * zoomChange).coerceIn(1.0, MAX_ZOOM),
                    )
                    // Store where the view really ended up, so panning past an edge doesn't build up slack.
                    val after = region(frame, moved) ?: return@detectTransformGestures
                    report?.invoke(moved.copy(x = (after.left + after.width / 2) / src.width, y = (after.top + after.height / 2) / src.height))
                }
            },
        ),
    ) {
        val r = region(size, current) ?: return@Canvas
        val src = painter.intrinsicSize
        val scale = (size.width / r.width).toFloat()
        translate(-(r.left * scale).toFloat(), -(r.top * scale).toFloat()) {
            with(painter) { draw(Size(src.width * scale, src.height * scale), colorFilter = colorFilter) }
        }
    }
}

/**
 * Each tile photo's width / height, by media id, filling in as the pictures load. The request is the same one
 * [CroppedPhoto] makes, so this is a cache hit, not a second download.
 */
@Composable
private fun rememberPictureAspects(tiles: List<SummaryTile>, pictures: Map<String, String>): Map<String, Double> {
    val context = LocalPlatformContext.current
    val wanted = tiles.mapNotNull { it.media }.distinct().mapNotNull { id -> pictures[id]?.let { id to it } }
    val known by produceState(emptyMap<String, Double>(), wanted) {
        for ((id, url) in wanted) {
            if (id in value) continue
            val image = (SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(PHOTO_PIXELS).build()) as? SuccessResult)?.image
            if (image != null && image.height > 0) value = value + (id to image.width.toDouble() / image.height)
        }
    }
    return known
}

/** The long-ish edge photos are decoded at for the editor: sharp on a phone, without loading full originals. */
private const val PHOTO_PIXELS = 1200

/** How far in a crop can zoom: past this, photos from a phone get visibly soft. */
const val MAX_ZOOM = 4.0
