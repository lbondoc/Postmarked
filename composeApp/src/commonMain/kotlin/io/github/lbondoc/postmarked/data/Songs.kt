package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.lbondoc.postmarked.domain.music.SongLink
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Song(
    val id: String,
    @SerialName("added_by") val addedBy: String? = null,
    val url: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    /** The trip day it goes with, or null for the whole trip. */
    val day: LocalDate? = null,
)

@Serializable
private class SongWrite(
    @SerialName("trip_id") val tripId: String,
    val provider: String,
    val url: String,
    val title: String,
    val artist: String?,
    @SerialName("artwork_url") val artworkUrl: String?,
    val day: LocalDate?,
)

/** What a music service's link preview says about a link. */
@Serializable
class SongPreview(val title: String, @SerialName("thumbnail_url") val artworkUrl: String? = null)

private val http by lazy { HttpClient() }
private val lenient = Json { ignoreUnknownKeys = true }

object Songs {
    suspend fun ofTrip(tripId: String): List<Song> =
        supabase.from("songs").select { filter { eq("trip_id", tripId) }; order("created_at", Order.ASCENDING) }.decodeList()

    /**
     * Title and artwork from Spotify's public link preview (oEmbed), which needs no account or
     * key. It doesn't include the artist, so people type that in if they want it credited.
     */
    suspend fun preview(link: SongLink): SongPreview =
        lenient.decodeFromString(http.get("https://open.spotify.com/oembed") { parameter("url", link.url) }.bodyAsText())

    suspend fun add(tripId: String, link: SongLink, preview: SongPreview, artist: String?, day: LocalDate?) {
        supabase.from("songs").insert(
            SongWrite(tripId, link.provider, link.url, preview.title.take(200), artist?.trim()?.ifEmpty { null }?.take(200), preview.artworkUrl, day),
        )
    }

    suspend fun delete(id: String) {
        supabase.from("songs").delete { filter { eq("id", id) } }
    }
}
