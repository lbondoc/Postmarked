package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.color.Filter
import io.github.lbondoc.postmarked.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * A one-tap summary look (SPEC §8.3), from files/presets/<id>.json, listed in files/presets/index.json. [layout],
 * [style] and [filter] are the names of a `Layout`, an automatic `SummaryMode` and a `Filter`; [harmonize] is the
 * "Match" amount. Paper, border and text style come from the trip's album theme, so presets don't set them.
 */
@Serializable
data class StylePreset(
    val id: String,
    val name: String,
    val layout: String,
    val style: String,
    val filter: String,
    val strength: Double = 1.0,
    val harmonize: Double = 0.0,
) {
    /** The summary-wide look this preset gives, or null for none (Original). */
    val look: LookData? get() = LookData(filter, strength).takeUnless { filter == Filter.Original.name }
}

object StylePresets {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var loaded: List<StylePreset>? = null

    /** All presets in index order. Read from the app's files once. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun all(): List<StylePreset> = lock.withLock {
        loaded ?: json.decodeFromString<List<String>>(Res.readBytes("files/presets/index.json").decodeToString())
            .map { json.decodeFromString<StylePreset>(Res.readBytes("files/presets/$it.json").decodeToString()) }
            .also { loaded = it }
    }
}
