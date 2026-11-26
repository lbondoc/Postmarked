package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.color.Filter
import io.github.lbondoc.postmarked.domain.summary.Layout
import io.github.lbondoc.postmarked.ui.SummaryMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Checks every style preset the app ships: it parses, is listed, and names things that exist. */
class PresetsTest {
    private val dir = File("src/commonMain/composeResources/files/presets")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everyPresetParsesIsListedAndNamesRealChoices() {
        val ids = json.decodeFromString<List<String>>(File(dir, "index.json").readText())
        val files = dir.listFiles().orEmpty().map { it.name }.filter { it != "index.json" }.map { it.removeSuffix(".json") }
        assertEquals(files.toSet(), ids.toSet(), "every file is listed in index.json, and every listed id has a file")
        assertEquals(ids.size, ids.toSet().size, "no id listed twice")
        assertTrue(ids.size in 5..8, "about six presets")
        for (id in ids) {
            val p = json.decodeFromString<StylePreset>(File(dir, "$id.json").readText())
            assertEquals(id, p.id)
            assertTrue(p.name.isNotBlank(), id)
            assertTrue(Layout.entries.any { it.name == p.layout }, "$id: layout ${p.layout}")
            assertTrue(SummaryMode.entries.any { it.name == p.style && it != SummaryMode.Manual }, "$id: style ${p.style}")
            assertTrue(Filter.entries.any { it.name == p.filter }, "$id: filter ${p.filter}")
            assertTrue(p.strength in 0.0..1.0 && p.harmonize in 0.0..1.0, id)
        }
    }

    @Test
    fun anOriginalPresetAddsNoLook() {
        assertEquals(null, StylePreset("a", "A", "Grid3x3", "Palette", "Original").look)
        assertEquals(LookData("Film", 0.9), StylePreset("b", "B", "Grid3x3", "Palette", "Film", 0.9).look)
    }
}
