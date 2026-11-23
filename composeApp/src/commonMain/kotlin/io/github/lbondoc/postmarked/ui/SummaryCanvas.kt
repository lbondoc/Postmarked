package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import coil3.compose.AsyncImage
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
 * a tile's colour-harmonize matrix (4 x 5, offsets 0..255), or null to show it untouched.
 * With [onSwap], long-press and drag a tile onto another to swap them; with [onTap], tap to edit.
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
    onSwap: ((Int, Int) -> Unit)? = null,
    onTap: ((Int) -> Unit)? = null,
) {
    val swap by rememberUpdatedState(onSwap)
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(layout.aspect.toFloat()).background(PAPER)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        var dragging by remember { mutableStateOf<Int?>(null) }
        var drag by remember { mutableStateOf(Offset.Zero) }

        fun slotAt(point: Offset) = layout.slots.indexOfFirst { s ->
            point.x in (s.x * width).toFloat()..((s.x + s.w) * width).toFloat() && point.y in (s.y * height).toFloat()..((s.y + s.h) * height).toFloat()
        }.takeIf { it >= 0 }

        layout.slots.forEachIndexed { i, slot ->
            val tile = tiles.getOrNull(i) ?: SummaryTile()
            // Layouts are fractions of the canvas; these are pixels.
            val left = (slot.x * width).toFloat()
            val top = (slot.y * height).toFloat()
            val w = (slot.w * width).toFloat()
            val h = (slot.h * height).toFloat()
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                    .zIndex(if (dragging == i) 1f else 0f)
                    // Outside graphicsLayer, so drag distances are measured on the unrotated canvas.
                    .then(
                        if (swap == null) Modifier else Modifier.pointerInput(layout, i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = i; drag = Offset.Zero },
                                onDrag = { change, amount -> change.consume(); drag += amount },
                                onDragEnd = {
                                    val target = slotAt(Offset(left + w / 2 + drag.x, top + h / 2 + drag.y))
                                    if (target != null && target != i) swap?.invoke(i, target)
                                    dragging = null
                                    drag = Offset.Zero
                                },
                                onDragCancel = { dragging = null; drag = Offset.Zero },
                            )
                        },
                    )
                    .graphicsLayer {
                        rotationZ = slot.rotation.toFloat()
                        if (dragging == i) {
                            translationX = drag.x
                            translationY = drag.y
                            scaleX = 1.06f
                            scaleY = 1.06f
                        }
                    }
                    .then(if (onTap == null) Modifier else Modifier.clickable(onClickLabel = "Change this tile") { onTap(i) })
                    .semantics { contentDescription = describe(tile, media) },
            ) {
                Framed(slot.frame) { TileContent(tile, media, pictures, gradeFor, textColours, w, density.density) }
            }
        }
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
        tile.media != null -> AsyncImage(
            model = pictures[tile.media],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // The look is applied while drawing; the photo itself is never changed.
            colorFilter = gradeFor(tile)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            modifier = Modifier.fillMaxSize().background(Color(0xFFE6E0D4)),
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
