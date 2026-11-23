package io.github.lbondoc.postmarked.data

import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.theme.ThemeColours
import io.github.lbondoc.postmarked.domain.theme.resolveColours
import io.github.lbondoc.postmarked.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Serializable
data class ThemeColoursSpec(val paper: String, val ink: String, val accent: String, val accent2: String) {
    fun toColours() = ThemeColours(hexToRgb(paper), hexToRgb(ink), hexToRgb(accent), hexToRgb(accent2))
}

/**
 * An album theme, read from files/themes/<id>.json. Adding a theme is adding a file and its id
 * to files/themes/index.json; the fields below are all a theme can set.
 */
@Serializable
data class AlbumTheme(
    val id: String,
    val name: String,
    val description: String = "",
    /** Take the accents from the trip's photos instead of the colours below. */
    val usePalette: Boolean = false,
    /** How the trip page's header looks: "band", "greetings" or "caption". */
    val header: String = "band",
    /** Border around exported images, as a share of their width. */
    val exportBorder: Double = 0.0,
    /** Border in the ink colour instead of the paper colour. */
    val exportBorderInk: Boolean = false,
    /** Edge of the picture inside the border: "plain", "deckled" or "film". */
    val exportEdge: String = "plain",
    /** A postmark in the corner of exported images. */
    val exportStamp: Boolean = false,
    val light: ThemeColoursSpec,
    val dark: ThemeColoursSpec,
)

/** A theme with its colours worked out for one trip, in light or dark mode. */
class AlbumLook(val theme: AlbumTheme, val colours: ThemeColours)

fun AlbumTheme.look(dark: Boolean, palette: List<Swatch>, customAccent: String?): AlbumLook =
    AlbumLook(this, resolveColours((if (dark) this.dark else light).toColours(), usePalette, palette, customAccent?.let(::hexToRgb)))

object AlbumThemes {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var loaded: List<AlbumTheme>? = null

    /** All themes in index order. Read from the app's files once. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun all(): List<AlbumTheme> = lock.withLock {
        loaded ?: json.decodeFromString<List<String>>(Res.readBytes("files/themes/index.json").decodeToString())
            .map { json.decodeFromString<AlbumTheme>(Res.readBytes("files/themes/$it.json").decodeToString()) }
            .also { loaded = it }
    }

    suspend fun byId(id: String?): AlbumTheme = all().let { themes -> themes.firstOrNull { it.id == id } ?: themes.first() }
}
