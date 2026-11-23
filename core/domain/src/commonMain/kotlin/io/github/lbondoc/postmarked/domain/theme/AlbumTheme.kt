package io.github.lbondoc.postmarked.domain.theme

import io.github.lbondoc.postmarked.domain.color.NEUTRAL_CHROMA
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.contrast
import kotlin.math.cos
import kotlin.math.sin

/** A theme's colours for one of light or dark mode, as 0xRRGGBB. */
data class ThemeColours(val paper: Int, val ink: Int, val accent: Int, val accent2: Int)

/** The minimum contrast for text (WCAG AA). Accents are used for headings and text tiles. */
const val TEXT_CONTRAST = 4.5

/**
 * The colours an album actually uses: the theme's own, or with [usePalette] its accents taken
 * from the trip's photos, or a [customAccent] the owner picked. Accents are then darkened (on
 * light paper) or lightened (on dark paper) until they read as text on the paper, keeping
 * their hue, so any palette passes the contrast check.
 */
fun resolveColours(base: ThemeColours, usePalette: Boolean, palette: List<Swatch>, customAccent: Int?): ThemeColours {
    val colourful = palette.filter { it.color.chroma >= NEUTRAL_CHROMA }.map { it.color.toRgb() }
    val accent = customAccent ?: colourful.firstOrNull()?.takeIf { usePalette } ?: base.accent
    val accent2 = colourful.getOrNull(if (customAccent == null) 1 else 0)?.takeIf { usePalette || customAccent != null } ?: base.accent2
    return base.copy(accent = readableAgainst(accent, base.paper), accent2 = readableAgainst(accent2, base.paper))
}

/** [colour] with its lightness moved, in small steps, until it reaches [TEXT_CONTRAST] against [background]. */
fun readableAgainst(colour: Int, background: Int): Int {
    if (contrast(colour, background) >= TEXT_CONTRAST) return colour
    val start = Oklab.fromRgb(colour)
    val darken = Oklab.fromRgb(background).l > 0.5
    var l = start.l
    var chroma = start.chroma
    val hue = start.hue * kotlin.math.PI / 180
    repeat(60) {
        l = (l + if (darken) -0.015 else 0.015).coerceIn(0.0, 1.0)
        chroma *= 0.98 // very dark or light colours can't stay as vivid
        val candidate = Oklab(l, chroma * cos(hue), chroma * sin(hue)).toRgb()
        if (contrast(candidate, background) >= TEXT_CONTRAST) return candidate
    }
    return if (darken) 0x000000 else 0xFFFFFF
}
