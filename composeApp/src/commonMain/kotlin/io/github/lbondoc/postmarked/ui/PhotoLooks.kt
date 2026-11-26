package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.lbondoc.postmarked.data.Crop
import io.github.lbondoc.postmarked.data.SummaryTile
import io.github.lbondoc.postmarked.data.toData
import io.github.lbondoc.postmarked.domain.color.Adjustment
import io.github.lbondoc.postmarked.domain.color.Filter
import io.github.lbondoc.postmarked.domain.color.Look
import kotlin.math.abs
import kotlin.math.roundToInt

fun matrixFilter(matrix: FloatArray?) = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }

// ---------- Controls shared by the summary editor and the photo editor ----------

/** One slider an editor offers: its name, lowest value (highest is 1), current value, and how to change it. */
class Tool(val label: String, val min: Double, val value: Double, val set: (Double) -> Unit)

private fun Tool.shown(): String {
    val percent = (value * 100).roundToInt()
    return if (min < 0 && percent > 0) "+$percent" else "$percent"
}

/**
 * A row of round tool buttons, each showing its value, and one big slider for the chosen tool,
 * so only one thing changes at a time.
 */
@Composable
fun ToolPicker(title: String, tools: List<Tool>, onReset: () -> Unit) {
    var chosen by remember { mutableIntStateOf(0) }
    val colours = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        TextButton(onClick = onReset, enabled = tools.any { it.value != 0.0 }) { Text("Reset", fontWeight = FontWeight.SemiBold) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEachIndexed { i, tool ->
            val on = i == chosen
            Column(
                Modifier.width(60.dp).selectable(on, role = Role.RadioButton) { chosen = i },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (on) colours.onSurface else Color.Transparent)
                        .border(2.dp, if (on) colours.onSurface else colours.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tool.value == 0.0) "·" else tool.shown(),
                        color = if (on) colours.surfaceContainerLowest else colours.onSurface,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(tool.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
    val tool = tools[chosen.coerceAtMost(tools.lastIndex)]
    Text("${tool.label} ${tool.shown()}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = tool.value.toFloat(),
        // Centred sliders click back to "no change" near the middle, so it's easy to find again.
        onValueChange = { v -> tool.set(if (tool.min < 0 && abs(v) < 0.03f) 0.0 else v.toDouble()) },
        valueRange = tool.min.toFloat()..1f,
        modifier = Modifier.semantics { contentDescription = tool.label },
        colors = SliderDefaults.colors(thumbColor = colours.onSurface, activeTrackColor = colours.onSurface),
    )
}

/**
 * The ready-made filters as small previews of a real photo ([thumbnail]) drawn through each one
 * ([previewOf] gives the colour matrix), plus a Strength slider once one is chosen.
 */
@Composable
fun FilterStrip(title: String, look: Look, thumbnail: String?, previewOf: (Filter) -> FloatArray?, onChange: (Look) -> Unit) {
    val colours = MaterialTheme.colorScheme
    SectionLabel(title)
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (filter in Filter.entries) {
            val on = filter == look.filter
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.width(66.dp).selectable(on, role = Role.RadioButton) { onChange(look.copy(filter = filter)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.size(62.dp)
                        .then(if (on) Modifier.border(2.dp, colours.onSurface, shape).padding(4.dp) else Modifier.padding(4.dp))
                        .clip(RoundedCornerShape(6.dp)).background(colours.surfaceVariant),
                ) {
                    if (thumbnail != null) AsyncImage(
                        model = thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                        colorFilter = matrixFilter(previewOf(filter)), modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(filter.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
    if (look.filter != Filter.Original) {
        Text("Strength ${(look.strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = look.strength.toFloat(), onValueChange = { onChange(look.copy(strength = it.toDouble())) },
            modifier = Modifier.semantics { contentDescription = "Filter strength" },
            colors = SliderDefaults.colors(thumbColor = colours.primary, activeTrackColor = colours.primary),
        )
    }
}

/** The sliders a look offers, as [Tool]s. */
fun lookTools(look: Look, adjustments: List<Adjustment>, onChange: (Look) -> Unit) = adjustments.map { a ->
    Tool(a.label, a.min, look.adjust[a] ?: 0.0) { v -> onChange(look.copy(adjust = look.adjust + (a to v))) }
}

// ---------- Editing one photo ----------

enum class PhotoTab(val label: String) { Filter("Filter"), Adjust("Adjust"), Crop("Crop") }

/**
 * A full-screen editor for one tile's photo, on a dark surround. Nothing is kept until Done.
 * [aspect] is the tile's shape (width / height), so the crop shows exactly what the tile will. In an [adaptive]
 * layout the tile takes the crop's chosen shape instead (SPEC §8.4); a strict grid keeps [aspect].
 * [gradeOf] draws a tile as the summary would, so the preview includes the summary's look.
 */
@Composable
fun EditPhotoScreen(
    tile: SummaryTile,
    picture: String?,
    thumbnail: String?,
    aspect: Float,
    adaptive: Boolean,
    startTab: PhotoTab,
    gradeOf: (SummaryTile) -> FloatArray?,
    onDone: (SummaryTile) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(tile) }
    var tab by remember { mutableStateOf(startTab) }
    var comparing by remember { mutableStateOf(false) }
    val look = draft.look?.toLook() ?: Look()
    fun setLook(l: Look) { draft = draft.copy(look = l.takeUnless { it.isPlain }?.toData()) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DarkroomTheme {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Text("Edit photo", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        InkButton("Done", onClick = { onDone(draft) })
                    }
                },
                bottomBar = { BottomTabs(PhotoTab.entries.map { it to it.label }, tab, { tab = it }, Modifier.navigationBarsPadding()) },
            ) { insets ->
                Column(
                    Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 380.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.aspectRatio(draft.crop?.aspect?.takeIf { adaptive }?.toFloat() ?: aspect)) {
                            CroppedPhoto(
                                picture, draft.crop, if (comparing) null else matrixFilter(gradeOf(draft)),
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                onCrop = if (tab == PhotoTab.Crop) { crop -> draft = draft.copy(crop = crop) } else null,
                            )
                            if (tab == PhotoTab.Crop) ThirdsGrid()
                            if (comparing) Text(
                                "Original", Modifier.padding(10.dp).background(Color(0xBF141312), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp).toggleable(!draft.ownLook, role = Role.Switch) { draft = draft.copy(ownLook = !it) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Switch(checked = !draft.ownLook, onCheckedChange = null) // the whole row is the switch
                            Text("Start from the summary's look", style = MaterialTheme.typography.bodyMedium)
                        }
                        CompareButton { comparing = it }
                    }
                    Text(
                        if (draft.ownLook) "This photo ignores the summary's filter and adjustments."
                        else "The summary's filter and adjustments come first; your changes here add to them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (tab) {
                        PhotoTab.Filter -> FilterStrip("Filter · this photo", look, thumbnail, { f -> gradeOf(draft.copy(look = look.copy(filter = f).toData())) }, ::setLook)
                        PhotoTab.Adjust -> ToolPicker("Adjust · this photo", lookTools(look, Adjustment.entries, ::setLook)) { setLook(look.copy(adjust = emptyMap())) }
                        PhotoTab.Crop -> {
                            val crop = draft.crop ?: Crop()
                            if (adaptive) CropShapes(crop) { draft = draft.copy(crop = it) }
                            else Text("Shape ${ratioLabel(aspect.toDouble())}: this layout's tiles have fixed shapes.", style = MaterialTheme.typography.labelLarge)
                            Text("Drag the photo to move it, pinch to zoom.", style = MaterialTheme.typography.bodyMedium)
                            Text("Zoom ${(crop.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = crop.zoom.toFloat(), onValueChange = { draft = draft.copy(crop = crop.copy(zoom = it.toDouble())) },
                                valueRange = 1f..MAX_ZOOM.toFloat(), modifier = Modifier.semantics { contentDescription = "Zoom" },
                                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface),
                            )
                            TextButton(onClick = { draft = draft.copy(crop = null) }, enabled = draft.crop != null) { Text("Reset crop") }
                        }
                    }
                }
            }
        }
    }
}

/** The crop shapes offered in adaptive layouts; null is the photo's own shape. */
private val CROP_SHAPES = listOf("Original" to null, "1:1" to 1.0, "4:5" to 4.0 / 5, "3:4" to 3.0 / 4, "16:9" to 16.0 / 9)

/** "4:5" for a preset shape, else the width / height to two places. */
private fun ratioLabel(a: Double): String =
    CROP_SHAPES.firstOrNull { it.second != null && abs(it.second!! - a) < 0.01 }?.first ?: ((a * 100).roundToInt() / 100.0).toString()

/** Original, the presets and Free (a slider from 1:2 to 2:1). Changing the shape reflows the layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropShapes(crop: Crop, onChange: (Crop) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, a) in CROP_SHAPES) InkChip(label, !crop.free && crop.aspect == a, { onChange(crop.copy(aspect = a, free = false)) })
        InkChip("Free", crop.free, { onChange(crop.copy(aspect = crop.aspect ?: 1.0, free = true)) })
    }
    if (crop.free) {
        val a = crop.aspect ?: 1.0
        Text("Shape ${ratioLabel(a)}", style = MaterialTheme.typography.labelLarge)
        // ln scale, so 1:2 and 2:1 sit the same distance from square.
        Slider(
            value = ln(a).toFloat(), onValueChange = { onChange(crop.copy(aspect = exp(it.toDouble()))) },
            valueRange = -ln(2f)..ln(2f), modifier = Modifier.semantics { contentDescription = "Crop shape" },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface, activeTrackColor = MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** Rule-of-thirds lines over the photo while cropping. */
@Composable
private fun ThirdsGrid() {
    Canvas(Modifier.fillMaxSize().border(1.5.dp, Color.White)) {
        val line = Color.White.copy(alpha = 0.55f)
        for (i in 1..2) {
            drawLine(line, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height))
            drawLine(line, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3))
        }
    }
}

/** Shows the untouched photo while held down. With TalkBack, a double tap switches it on and off instead. */
@Composable
private fun CompareButton(onComparing: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(false) }
    fun set(v: Boolean) { on = v; onComparing(v) }
    Box(
        Modifier.height(44.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onPress = { set(true); try { awaitRelease() } finally { set(false) } }) }
            .semantics { role = Role.Button; contentDescription = "Compare with the original"; onClick { set(!on); true } }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Hold to compare", style = MaterialTheme.typography.labelLarge) }
}
