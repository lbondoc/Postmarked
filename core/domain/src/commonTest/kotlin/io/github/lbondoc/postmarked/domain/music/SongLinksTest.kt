package io.github.lbondoc.postmarked.domain.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongLinksTest {
    private val id = "4cOdK2wGLETKBW3PvgPWqT"

    @Test
    fun readsTheLinksPeopleActuallyPaste() {
        val plain = SongLink("spotify", "track", id)
        assertEquals(plain, parseSongLink("https://open.spotify.com/track/$id"))
        assertEquals(plain, parseSongLink("https://open.spotify.com/track/$id?si=a1b2c3d4e5f6"), "share links carry tracking")
        assertEquals(plain, parseSongLink("https://open.spotify.com/intl-de/track/$id?si=x"), "and a country")
        assertEquals(plain, parseSongLink("  spotify:track:$id  "))
        assertEquals("https://open.spotify.com/track/$id", plain.url)
    }

    @Test
    fun albumsPlaylistsAndEpisodesToo() {
        assertEquals("album", parseSongLink("https://open.spotify.com/album/1A2GTWGtFfWp7KSQTwWOyo")?.kind)
        assertEquals("playlist", parseSongLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=1")?.kind)
        assertEquals("episode", parseSongLink("spotify:episode:$id")?.kind)
    }

    @Test
    fun anythingElseIsNotASong() {
        assertNull(parseSongLink(""))
        assertNull(parseSongLink("Never Gonna Give You Up"))
        assertNull(parseSongLink("https://open.spotify.com/artist/$id"), "an artist isn't something to play on a trip card")
        assertNull(parseSongLink("https://open.spotify.com/track/tooShort"))
        assertNull(parseSongLink("https://evil.example/open.spotify.com/track/$id"))
        assertNull(parseSongLink("https://open.spotify.com.evil.example/track/$id"))
    }
}
