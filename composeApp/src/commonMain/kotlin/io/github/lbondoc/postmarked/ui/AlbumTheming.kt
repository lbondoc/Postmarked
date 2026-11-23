package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.data.AlbumLook
import io.github.lbondoc.postmarked.data.AlbumTheme
import io.github.lbondoc.postmarked.data.AlbumThemes
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.TripMedia
import io.github.lbondoc.postmarked.data.look
import io.github.lbondoc.postmarked.domain.color.NEUTRAL_CHROMA
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.colourName
import io.github.lbondoc.postmarked.domain.color.rgbToHex

/**
 * The trip's album look. [forExport]: always the light variant, so summaries and the images
 * made from them look the same whatever mode the phone is in. Null while themes load.
 */
@Composable
fun rememberAlbumLook(trip: Trip?, media: List<TripMedia>, forExport: Boolean = false): AlbumLook? {
    val dark = isSystemInDarkTheme() && !forExport
    val themes by produceState(emptyList<AlbumTheme>()) { value = AlbumThemes.all() }
    val palette = remember(media) { tripPalette(media) }
    val theme = themes.firstOrNull { it.id == trip?.themeId } ?: themes.firstOrNull() ?: return null
    return remember(theme, trip?.customAccent, palette, dark) { theme.look(dark, palette, trip?.customAccent) }
}

/** The top of a trip page, drawn in the album theme. */
@Composable
fun AlbumHeader(trip: Trip, look: AlbumLook) {
    val c = look.colours
    val paper = rgbColor(c.paper)
    val ink = rgbColor(c.ink)
    val accent = rgbColor(c.accent)
    val place = trip.regions.firstOrNull()?.label?.substringBefore(',') ?: trip.name
    when (look.theme.header) {
        // Postcard: "Greetings from" lettering, the place in big type, a postmark.
        "greetings" -> Row(Modifier.fillMaxWidth().background(paper).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GREETINGS FROM", color = ink, fontFamily = headingFont(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 2.sp)
                Text(place.uppercase(), color = accent, fontFamily = headingFont(), fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp)
                Text("${trip.name} · ${trip.dates}", color = ink, style = MaterialTheme.typography.bodyMedium)
            }
            PostmarkLogo(size = 64.dp, colour = rgbColor(c.accent2))
        }
        // Film: sprocket holes above and below a handwritten caption.
        "caption" -> Column(Modifier.fillMaxWidth().background(paper)) {
            Sprockets(ink, paper)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(trip.name, color = ink, fontFamily = handFont(), fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 36.sp)
                Text(trip.dates, color = accent, style = MaterialTheme.typography.bodyMedium)
            }
            Sprockets(ink, paper)
        }
        // A band of the accent colour (Auto Palette takes it from the photos); the colours are checked to be readable.
        else -> Column(Modifier.fillMaxWidth().background(accent).padding(16.dp)) {
            Text(trip.name.uppercase(), color = paper, fontFamily = headingFont(), fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp)
            Text(trip.dates, color = paper, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Sprockets(film: androidx.compose.ui.graphics.Color, hole: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.fillMaxWidth().height(14.dp).background(film)) {
        val w = 10.dp.toPx()
        val h = 6.dp.toPx()
        var x = 6.dp.toPx()
        while (x + w < size.width) {
            drawRoundRect(hole, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(1.5.dp.toPx()))
            x += w * 2
        }
    }
}

/** For owners and editors: choose the album theme and, optionally, its accent colour. Folded away until opened. */
@Composable
fun ThemePicker(trip: Trip, palette: List<Swatch>, onPick: (themeId: String, accent: String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val themes by produceState(emptyList<AlbumTheme>()) { value = AlbumThemes.all() }
    val current = themes.firstOrNull { it.id == trip.themeId } ?: themes.firstOrNull() ?: return
    TextButton(onClick = { open = !open }) { Text("Album theme: ${current.name} " + if (open) "▴" else "▾") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (theme in themes) FilterChip(
                selected = theme.id == current.id,
                onClick = { onPick(theme.id, trip.customAccent) },
                label = { Text(theme.name) },
            )
        }
        Text(current.description, style = MaterialTheme.typography.bodySmall)
        Text("Accent colour", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = trip.customAccent == null,
                onClick = { onPick(current.id, null) },
                label = { Text(if (current.usePalette) "From the photos" else "The theme's own") },
            )
            // The accent comes from the trip's hues only; a grey or white accent would vanish against the paper.
            for (swatch in palette.filter { it.color.chroma >= NEUTRAL_CHROMA }) {
                val hex = rgbToHex(swatch.rgb)
                FilterChip(
                    selected = trip.customAccent == hex,
                    onClick = { onPick(current.id, hex) },
                    label = { Text(colourName(swatch.color)) },
                    leadingIcon = { Box(Modifier.size(16.dp).background(rgbColor(swatch.rgb), CircleShape)) },
                )
            }
        }
        Text("Colours are adjusted where needed so text stays readable.", style = MaterialTheme.typography.bodySmall)
    }
}
