package io.github.lbondoc.postmarked.domain.theme

import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.contrast
import io.github.lbondoc.postmarked.domain.color.hueDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumThemeTest {
    private val light = ThemeColours(paper = 0xF4EFE4, ink = 0x23201C, accent = 0xB5452F, accent2 = 0x2F5D50)
    private val dark = ThemeColours(paper = 0x1C1B19, ink = 0xEDE6D6, accent = 0xF0A830, accent2 = 0x7FB2A5)
    private fun palette(vararg rgb: Int) = rgb.map { Swatch(Oklab.fromRgb(it), 1.0 / rgb.size) }

    // Colours that are hard to read on paper: pale yellow, sky blue, pastel pink, white, near-black.
    private val awkward = listOf(0xF7E27A, 0x8EC9F0, 0xF5B8C8, 0xFFFFFF, 0x151515, 0x777777)

    @Test
    fun everyAccentFromAnyPaletteIsReadableInLightAndDark() {
        for (base in listOf(light, dark)) for (a in awkward) for (b in awkward) {
            val resolved = resolveColours(base, usePalette = true, palette(a, b), customAccent = null)
            assertTrue(contrast(resolved.accent, base.paper) >= TEXT_CONTRAST, "accent from ${a.toString(16)} on ${base.paper.toString(16)}")
            assertTrue(contrast(resolved.accent2, base.paper) >= TEXT_CONTRAST)
        }
    }

    @Test
    fun aDarkenedAccentKeepsItsHue() {
        val yellow = 0xF2C230
        val fixed = readableAgainst(yellow, light.paper)
        assertTrue(contrast(fixed, light.paper) >= TEXT_CONTRAST)
        assertTrue(hueDistance(Oklab.fromRgb(fixed).hue, Oklab.fromRgb(yellow).hue) < 12, "still a yellow-brown, not grey")
    }

    @Test
    fun readableColoursAreLeftAlone() = assertEquals(0xB5452F, readableAgainst(0xB5452F, light.paper))

    @Test
    fun theThemesOwnColoursAreUsedUnlessAskedOtherwise() {
        val photos = palette(0x2A9D8F, 0xE76F51)
        assertEquals(light, resolveColours(light, usePalette = false, photos, customAccent = null))
        val fromPhotos = resolveColours(light, usePalette = true, photos, customAccent = null)
        assertTrue(hueDistance(Oklab.fromRgb(fromPhotos.accent).hue, Oklab.fromRgb(0x2A9D8F).hue) < 12, "Auto Palette takes the photos' main colour")
        val custom = resolveColours(light, usePalette = false, photos, customAccent = 0x3B4F7A)
        assertEquals(0x3B4F7A, custom.accent, "the owner's pick wins")
    }

    @Test
    fun greyPhotosFallBackToTheThemesAccent() =
        assertEquals(light.accent, resolveColours(light, usePalette = true, palette(0x808080, 0xEEEEEE), null).accent)
}
