package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.color.contrast
import io.github.lbondoc.postmarked.domain.theme.TEXT_CONTRAST
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Checks every theme file the app ships: it parses, and its own colours pass contrast in both modes. */
class AlbumThemesTest {
    private val dir = File("src/commonMain/composeResources/files/themes")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everyListedThemeParsesAndIsReadable() {
        val ids = json.decodeFromString<List<String>>(File(dir, "index.json").readText())
        assertEquals(listOf("auto_palette", "postcard", "film"), ids)
        for (id in ids) {
            val theme = json.decodeFromString<AlbumTheme>(File(dir, "$id.json").readText())
            assertEquals(id, theme.id)
            assertTrue(theme.header in setOf("band", "greetings", "caption"), id)
            assertTrue(theme.exportEdge in setOf("plain", "deckled", "film"), id)
            for ((mode, spec) in listOf("light" to theme.light, "dark" to theme.dark)) {
                val c = spec.toColours()
                assertTrue(contrast(c.ink, c.paper) >= 7.0, "$id $mode: ink on paper")
                assertTrue(contrast(c.accent, c.paper) >= TEXT_CONTRAST, "$id $mode: accent on paper")
                assertTrue(contrast(c.accent2, c.paper) >= TEXT_CONTRAST, "$id $mode: second accent on paper")
            }
        }
    }
}
