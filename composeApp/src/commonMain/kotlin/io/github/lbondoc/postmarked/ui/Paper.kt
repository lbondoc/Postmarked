package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The ink-on-paper pieces the calmer screens are built from. They take their colours from the
// theme, so they follow the phone's light and dark setting: "ink" is onSurface, "paper" is
// surfaceContainerLowest (the whitest card colour).

private val Pill = RoundedCornerShape(50)

/** A small spaced-out capital heading over a group of controls, e.g. "COLOUR". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.semantics { heading() },
        fontFamily = headingFont(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A white card with softly rounded corners, the "paper" panels sit on. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** The main action on a screen: an ink-dark pill. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/**
 * An on/off choice: filled with ink when on, a paper pill with a hairline edge when off.
 * [count] is shown faintly after the label; [enabled] = false fades it (nothing would match).
 */
@Composable
fun InkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colours = MaterialTheme.colorScheme
    val ink = if (selected) colours.surfaceContainerLowest else colours.onSurface
    Row(
        modifier
            .alpha(if (enabled || selected) 1f else 0.4f)
            .heightIn(min = 40.dp)
            .clip(Pill)
            .background(if (selected) colours.onSurface else colours.surfaceContainerLowest)
            .border(1.5.dp, if (selected) colours.onSurface else colours.outlineVariant, Pill)
            // A chip that would show nothing can still be switched off, never on.
            .toggleable(selected, enabled = enabled || selected, role = Role.Checkbox) { onClick() }
            .padding(start = if (leading == null) 14.dp else 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(text, color = ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (count != null) Text("$count", color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A round initial, for a person's chip. */
@Composable
fun Initial(name: String, colour: Color) {
    Box(Modifier.size(30.dp).background(colour, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/**
 * A colour choice drawn as a postage stamp: the colour on a white frame, tilted a little when off
 * and squared up with an ink ring when on. [tilt] alternates the lean so a row looks hand-placed.
 */
@Composable
fun StampSwatch(
    colour: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
    tilt: Float = 0f,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .width(64.dp)
            .alpha(if (enabled || selected) 1f else 0.4f)
            .toggleable(selected, enabled = enabled || selected, role = Role.Checkbox) { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 52.dp)
                .rotate(if (selected) 0f else tilt)
                .then(if (selected) Modifier.border(2.dp, ink, RoundedCornerShape(2.dp)).padding(3.dp) else Modifier.padding(3.dp))
                .shadow(if (selected) 0.dp else 2.dp, RoundedCornerShape(1.dp))
                .background(Color.White)
                .padding(4.dp),
        ) { Box(Modifier.fillMaxSize().background(colour)) }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1)
        if (count != null) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pick one of a few: a sunken track with the chosen option raised on a paper pill. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, chosen: T, onChoose: (T) -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    Row(modifier.clip(Pill).background(colours.surfaceVariant).padding(3.dp).selectableGroup()) {
        for ((value, label) in options) {
            val on = value == chosen
            Box(
                Modifier.weight(1f).heightIn(min = 38.dp)
                    .then(if (on) Modifier.shadow(1.dp, Pill).background(colours.surfaceContainerLowest, Pill) else Modifier)
                    .clip(Pill)
                    .selectable(on, role = Role.RadioButton) { onChoose(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A bottom bar of tabs where only the chosen one's panel shows: a short red bar marks it. */
@Composable
fun <T> BottomTabs(options: List<Pair<T, String>>, chosen: T, onChoose: (T) -> Unit, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().background(colours.surfaceContainerLow).padding(horizontal = 6.dp).selectableGroup()) {
        for ((value, label) in options) tab(value == chosen, label) { onChoose(value) }
    }
}

@Composable
private fun RowScope.tab(on: Boolean, label: String, onClick: () -> Unit) {
    val colours = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).heightIn(min = 56.dp).selectable(on, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(width = 28.dp, height = 4.dp).background(if (on) colours.primary else Color.Transparent, Pill))
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (on) colours.onSurface else colours.onSurfaceVariant,
        )
    }
}
