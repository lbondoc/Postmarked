package io.github.lbondoc.postmarked.domain.music

/** A song (or album, playlist, podcast episode) on a music service, as a link people can open. */
data class SongLink(val provider: String, val kind: String, val id: String) {
    /** The canonical web link: opens the service's app when installed, the web player otherwise. */
    val url: String get() = when (provider) {
        "spotify" -> "https://open.spotify.com/$kind/$id"
        else -> error("Unknown provider $provider")
    }
}

// Share links can carry a country segment (intl-de) and tracking (?si=...); both are dropped.
private val SPOTIFY_WEB = Regex("""^https?://open\.spotify\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist|episode)/([A-Za-z0-9]{22})(?:[/?#].*)?$""")
private val SPOTIFY_URI = Regex("""^spotify:(track|album|playlist|episode):([A-Za-z0-9]{22})$""")

/**
 * Reads a pasted link. Null when it isn't one we recognise.
 * shortcut: Spotify only; Apple Music and YouTube Music links get their own patterns here when added.
 */
fun parseSongLink(text: String): SongLink? {
    val t = text.trim()
    val match = SPOTIFY_WEB.find(t) ?: SPOTIFY_URI.find(t) ?: return null
    val (kind, id) = match.destructured
    return SongLink("spotify", kind, id)
}
