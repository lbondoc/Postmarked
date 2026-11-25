package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lbondoc.postmarked.data.Summary

/** The steps of a first summary. [Colour] only comes up for a one-colour summary. */
private enum class GuideStep(val label: String, val title: String, val detail: String) {
    Style("style", "What kind of summary?", "A summary is a grid of your trip's photos, picked by colour. You can change everything later."),
    Colour("colour", "Which colour should your grid be?", "We'll pick the photos that are mostly this colour. You can swap any of them later."),
    Layout("layout", "How should the photos be arranged?", "Each layout shows a different number of photos."),
    Look("look", "Give it a look", "A filter for every photo. Original keeps them as they are."),
}

/**
 * Someone's first summary, one decision per screen with a live preview, instead of the whole
 * editor at once. Finishing (or Skip) opens the normal editor with everything chosen so far.
 */
@Composable
internal fun GuidedSummary(
    vm: SummaryEditorViewModel,
    material: SummaryMaterial,
    summary: Summary,
    onClose: () -> Unit,
    onChooseMine: () -> Unit,
) {
    var step by remember { mutableStateOf(GuideStep.Style) }
    val steps = GuideStep.entries.filter { it != GuideStep.Colour || vm.mode == SummaryMode.Monochrome }
    val at = steps.indexOf(step).coerceAtLeast(0)
    val next = steps.getOrNull(at + 1)
    val colours = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Close") }
                    Text("Step ${at + 1} of ${steps.size}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { vm.guided = false }) { Text("Skip") }
                }
                // How far along: one bar per step, filled up to this one.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { i, _ ->
                        Box(Modifier.weight(1f).height(4.dp).background(if (i <= at) colours.primary else colours.outlineVariant, RoundedCornerShape(2.dp)))
                    }
                }
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (at > 0) OutlinedButton(onClick = { step = steps[at - 1] }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                InkButton(
                    if (next != null) "Next: ${next.label}" else "Finish",
                    onClick = { if (next != null) step = next else vm.guided = false },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(step.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text(step.detail, style = MaterialTheme.typography.bodyLarge, color = colours.onSurfaceVariant)
            PaperCard(Modifier.fillMaxWidth()) {
                when (step) {
                    GuideStep.Style -> StylePanel(vm.mode, summary, material, presets = emptyList(), onPreset = {}) { mode -> if (mode == SummaryMode.Manual) onChooseMine() else vm.setMode(mode) }
                    GuideStep.Colour -> ColourPanel(vm.mode, summary, material, vm::setFocus)
                    GuideStep.Layout -> LayoutPanel(vm.layout, vm::setLayout)
                    GuideStep.Look -> WholeSummaryFilter(summary, material, vm::setLook)
                }
            }
            // The summary so far, small, so each choice shows straight away.
            val look = rememberAlbumLook(material.trip, material.media, forExport = true)
            val paper = look?.let { rgbColor(it.colours.paper) } ?: Color(0xFFF7F3EA)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(0.62f).shadow(4.dp).background(paper).padding(6.dp)) {
                    SummaryCanvas(vm.layout, summary.tiles, material.byId, material.pictures, material.gradeFor(summary), material.textColours(summary), paper = paper)
                }
            }
            Text("Your summary so far", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
        }
    }
}
