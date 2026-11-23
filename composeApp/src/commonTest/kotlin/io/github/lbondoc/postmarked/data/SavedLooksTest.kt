package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.color.Adjustment
import io.github.lbondoc.postmarked.domain.color.Filter
import io.github.lbondoc.postmarked.domain.color.Look
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class SavedLooksTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun tilesSavedBeforeCropsAndLooksStillLoad() {
        val tile = json.decodeFromString<SummaryTile>("""{"media": "abc", "noGrade": true}""")
        assertEquals(SummaryTile(media = "abc", noGrade = true), tile)
    }

    @Test
    fun namesFromANewerVersionAreIgnored() {
        val saved = json.decodeFromString<LookData>("""{"filter": "Neon", "strength": 0.5, "adjust": {"Warmth": 0.2, "Sparkle": 1}}""")
        assertEquals(Look(Filter.Original, 0.5, mapOf(Adjustment.Warmth to 0.2)), saved.toLook())
    }

    @Test
    fun aLookSurvivesSavingAndLoading() {
        val look = Look(Filter.Golden, 0.8, mapOf(Adjustment.Fade to 0.3, Adjustment.Light to 0.0))
        val back = json.decodeFromString<LookData>(json.encodeToString(LookData.serializer(), look.toData())).toLook()
        assertEquals(look.copy(adjust = mapOf(Adjustment.Fade to 0.3)), back) // untouched sliders aren't stored
    }

    @Test
    fun aCropKeepsItsRatio() {
        val tile = SummaryTile(media = "abc", crop = Crop(x = 0.4, y = 0.6, zoom = 1.5, ratio = io.github.lbondoc.postmarked.domain.summary.CropRatio.Wide.name))
        val back = json.decodeFromString<SummaryTile>(json.encodeToString(SummaryTile.serializer(), tile))
        assertEquals(tile, back)
        assertEquals(io.github.lbondoc.postmarked.domain.summary.CropRatio.Wide, back.crop!!.cropRatio)
    }

    @Test
    fun cropsSavedBeforeRatiosAreOriginal() {
        val old = json.decodeFromString<Crop>("""{"x": 0.5, "y": 0.4, "zoom": 1.6}""")
        assertEquals(io.github.lbondoc.postmarked.domain.summary.CropRatio.Original, old.cropRatio)
        val newer = json.decodeFromString<Crop>("""{"x": 0.5, "y": 0.5, "zoom": 1, "ratio": "Cinema"}""") // a ratio a newer version adds
        assertEquals(io.github.lbondoc.postmarked.domain.summary.CropRatio.Original, newer.cropRatio)
    }
}
