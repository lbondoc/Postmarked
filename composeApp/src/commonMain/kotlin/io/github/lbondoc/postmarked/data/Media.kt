package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import io.github.lbondoc.postmarked.domain.color.Oklab
import io.github.lbondoc.postmarked.domain.color.Swatch
import io.github.lbondoc.postmarked.domain.color.hexToRgb
import io.github.lbondoc.postmarked.domain.color.palette
import io.github.lbondoc.postmarked.domain.color.rgbToHex
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class MediaKind {
    @SerialName("photo") Photo,
    @SerialName("video") Video;

    /** The files stored for each item. The storage rules in supabase/migrations accept only these names. */
    val files: List<String>
        get() = when (this) {
            Photo -> listOf(ORIGINAL, DISPLAY, THUMB)
            Video -> listOf(VIDEO, POSTER, THUMB)
        }

    companion object {
        const val ORIGINAL = "original" // the camera's own file, GPS and all
        const val DISPLAY = "display.jpg" // 2048 px on the long side
        const val VIDEO = "video.mp4" // capped at 1080p; the original video stays on the phone
        const val POSTER = "poster.jpg" // a frame from the video, sized like DISPLAY
        const val THUMB = "thumb.jpg" // 400 px on the long side, for grids
    }
}

/** A photo or video in this phone's camera roll. */
@Serializable
data class DeviceMedia(
    val uri: String,
    val kind: MediaKind,
    val capturedAtMs: Long,
    /** The calendar day where it was taken when the camera recorded local time, else in the phone's time zone. */
    val takenOn: LocalDate,
    val utcOffsetMinutes: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    // A property with only a getter is not stored, so it is not part of the saved JSON.
    val location: LatLng? get() = if (lat != null && lng != null) LatLng(lat, lng) else null
}

/** One item in this phone's upload queue. Finished items stay so the import screen can mark them as added. */
@Serializable
data class QueuedUpload(
    /** Chosen when queued, so a retry reuses the same storage folder and database id. */
    val mediaId: String,
    val tripId: String,
    val item: DeviceMedia,
    /** Files already uploaded: an interrupted upload continues with the rest. */
    val uploadedFiles: Set<String> = emptySet(),
    val finished: Boolean = false,
    /** Why it can't be uploaded, when trying again won't help. */
    val problem: String? = null,
    /** Failed attempts in a row that looked temporary (a dropped connection, a server hiccup). */
    val attempts: Int = 0,
    /** When it was picked; groups items into the batch a progress bar counts. */
    val queuedAtMs: Long = 0,
) {
    val waiting: Boolean get() = !finished && problem == null
}

class BatchProgress(val done: Int, val total: Int, val fraction: Float)

/**
 * Progress through the current batch: everything queued since the oldest item still waiting,
 * so a new batch starts from zero rather than counting every upload ever made from this phone.
 * [partial] is how far through its files each in-flight item is, 0 to 1. Items that failed
 * for good leave the count. Null when nothing is waiting.
 */
fun batchProgress(uploads: List<QueuedUpload>, partial: Map<String, Float>): BatchProgress? {
    val start = uploads.filter { it.waiting }.minOfOrNull { it.queuedAtMs } ?: return null
    val batch = uploads.filter { it.queuedAtMs >= start && it.problem == null }
    val done = batch.count { it.finished }
    val inFlight = batch.filter { it.waiting }.sumOf { (partial[it.mediaId] ?: 0f).toDouble() }
    return BatchProgress(done, batch.size, ((done + inFlight) / batch.size).toFloat())
}

/**
 * The queue with [ids] cancelled: waiting, uploading and failed items go; finished ones stay (they are in the
 * gallery, and deleting them is the gallery's job). Returns the new queue and the items cancelled, whose partly
 * uploaded files the caller removes with [Media.discardUpload].
 */
fun cancelUploads(queue: List<QueuedUpload>, ids: Set<String>): Pair<List<QueuedUpload>, List<QueuedUpload>> {
    val (cancelled, kept) = queue.partition { it.mediaId in ids && !it.finished }
    return kept to cancelled
}

class ScanResult(val items: List<DeviceMedia>, val locationAllowed: Boolean)

/** What only the platform can do with the phone's photos: Android today, iOS later. */
interface CameraRoll {
    /** Asks for photo access if it is missing. False when the user refuses. */
    suspend fun requestAccess(): Boolean

    /** Photos and videos taken from [fromMs] up to [untilMs], with their GPS read. */
    suspend fun scan(fromMs: Long, untilMs: Long): ScanResult

    /** Everything this phone has queued for any trip. */
    val uploads: StateFlow<List<QueuedUpload>>

    /** How far through its files each uploading item is, 0 to 1, by media id. */
    val uploadProgress: StateFlow<Map<String, Float>>

    /** Queues [items] for [tripId]; they upload in the background, Wi-Fi only unless [mobileData] is on. */
    fun upload(tripId: String, items: List<DeviceMedia>)

    val mobileData: StateFlow<Boolean>

    fun allowMobileData(allowed: Boolean)

    /**
     * Cancels these queued items (by media id): one that is uploading stops, and any files it already sent
     * are deleted. Finished items stay.
     */
    fun cancel(mediaIds: Set<String>)

    /** What the import grid shows for [item]: anything Coil loads. The phone's content:// address works as it is. */
    fun preview(item: DeviceMedia): Any? = item.uri
}

/** A photo or video in a trip, as the gallery and map see it. */
@Serializable
data class TripMedia(
    val id: String,
    val kind: MediaKind,
    val contributor: String,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("utc_offset_minutes") val utcOffsetMinutes: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val dhash: Long? = null,
    val sharpness: Double? = null,
    /** Its main colours, largest first; null until worked out. */
    val palette: List<PaletteEntry>? = null,
    /** Who took it. */
    val profile: Profile? = null,
) {
    val swatches: List<Swatch> get() = palette.orEmpty().map { it.toSwatch() }

    val location: LatLng? get() = if (lat != null && lng != null) LatLng(lat, lng) else null
    val takenAt: Instant get() = Instant.parse(capturedAt)

    /** Clock time where it was taken when the camera recorded the time zone, else in the phone's zone. */
    val localTime: LocalDateTime
        get() = if (utcOffsetMinutes != null) (takenAt + utcOffsetMinutes.minutes).toLocalDateTime(TimeZone.UTC)
        else takenAt.toLocalDateTime(TimeZone.currentSystemDefault())

    val takenOn: LocalDate get() = localTime.date
}

/** One colour of a palette as stored: hex colour and share of the picture. */
@Serializable
data class PaletteEntry(val c: String, val w: Double) {
    fun toSwatch() = Swatch(Oklab.fromRgb(hexToRgb(c)), w)
}

fun List<Swatch>.toEntries() = map { PaletteEntry(rgbToHex(it.rgb), (it.weight * 1000).roundToInt() / 1000.0) }

@Serializable
data class Reaction(@SerialName("user_id") val userId: String, val emoji: String)

@Serializable
data class Comment(
    val id: String,
    val author: String,
    val body: String,
    val profile: Profile? = null,
)

/** The reactions people can leave; the database accepts only these. */
val REACTIONS = listOf("❤️", "😂", "😮", "👍", "🔥")

/** A trip's photos and videos on the server. */
object Media {
    private val bucket get() = supabase.storage.from("media")

    fun path(tripId: String, mediaId: String, file: String) = "$tripId/$mediaId/$file"

    suspend fun ofTrip(tripId: String): List<TripMedia> =
        supabase.from("media").select(
            // lat and lng are computed columns (see the media_location migration); profile is who took it.
            // "!contributor" names the link: reactions and comments also join media to profiles, so without it the server can't tell which.
            Columns.raw("id,kind,contributor,captured_at,utc_offset_minutes,lat,lng,dhash,sharpness,palette,profile:profiles!contributor(display_name)")
        ) {
            filter { eq("trip_id", tripId) }
            order("captured_at", Order.ASCENDING)
        }.decodeList()

    /** True when this exact file is already in the trip, from anyone. */
    suspend fun exists(tripId: String, sha256: String): Boolean =
        supabase.from("media").select(Columns.list("id")) {
            filter { eq("trip_id", tripId); eq("sha256", sha256) }
        }.decodeList<JsonObject>().isNotEmpty()

    /** Adds the database row once all files are up. False when someone else added the same file first. */
    suspend fun add(upload: QueuedUpload, sha256: String, dhash: Long?, sharpness: Double?, palette: List<Swatch>): Boolean = try {
        supabase.from("media").insert(buildJsonObject {
            put("id", upload.mediaId)
            put("trip_id", upload.tripId)
            put("kind", upload.item.kind.name.lowercase())
            put("captured_at", Instant.fromEpochMilliseconds(upload.item.capturedAtMs).toString())
            upload.item.utcOffsetMinutes?.let { put("utc_offset_minutes", it) }
            upload.item.location?.let { put("location", "SRID=4326;POINT(${it.lng} ${it.lat})") }
            put("sha256", sha256)
            dhash?.let { put("dhash", it) }
            sharpness?.let { put("sharpness", it) }
            put("palette", Json.encodeToJsonElement(palette.toEntries()))
        })
        true
    } catch (e: PostgrestRestException) {
        if (e.code == "23505") false else throw e // 23505: Postgres "unique violation"
    }

    /**
     * Removes what a cancelled upload left behind: its database row, if it got that far, and every file it may
     * have sent. Deleting files that were never uploaded is harmless, which covers a file finishing just as the
     * upload is cancelled.
     */
    suspend fun discardUpload(upload: QueuedUpload) {
        supabase.from("media").delete { filter { eq("id", upload.mediaId) } }
        deleteFiles(upload.item.kind.files.map { path(upload.tripId, upload.mediaId, it) })
    }

    suspend fun deleteFiles(paths: List<String>) {
        paths.chunked(500).forEach { bucket.delete(it) }
    }

    /** Removes every file of every item in the trip. The trip's owner is allowed to. */
    suspend fun deleteAllFiles(tripId: String) =
        deleteFiles(ofTrip(tripId).flatMap { m -> m.kind.files.map { path(tripId, m.id, it) } })

    /** Short-lived links to each item's thumbnail, by media id. The bucket is private, so plain links don't work. */
    suspend fun thumbnailUrls(tripId: String, ids: List<String>): Map<String, String> = signedUrls(tripId, ids, MediaKind.THUMB)

    private suspend fun signedUrls(tripId: String, ids: List<String>, file: String): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val byPath = bucket.createSignedUrls(1.hours, ids.map { path(tripId, it, file) }).associate { it.path to it.signedURL }
        return ids.mapNotNull { id -> byPath[path(tripId, id, file)]?.let { id to it } }.toMap()
    }

    /** Each trip's cover thumbnail, by trip id, in one request for the whole trip list. */
    suspend fun coverUrls(trips: List<Trip>): Map<String, String> {
        val covers = trips.mapNotNull { trip -> trip.coverMediaId?.let { trip.id to path(trip.id, it, MediaKind.THUMB) } }
        if (covers.isEmpty()) return emptyMap()
        val byPath = bucket.createSignedUrls(1.hours, covers.map { it.second }).associate { it.path to it.signedURL }
        return covers.mapNotNull { (trip, p) -> byPath[p]?.let { trip to it } }.toMap()
    }

    /** A link to the full-size picture, or to the video itself. */
    suspend fun viewUrl(tripId: String, item: TripMedia): String =
        signedUrls(tripId, listOf(item.id), if (item.kind == MediaKind.Video) MediaKind.VIDEO else MediaKind.DISPLAY).getValue(item.id)

    /** The item's own file, the original photo or the video, to save or share; named by when it was taken. */
    suspend fun savedFile(tripId: String, item: TripMedia): SavedFile {
        val file = if (item.kind == MediaKind.Video) MediaKind.VIDEO else MediaKind.ORIGINAL
        val url = bucket.createSignedUrl(path(tripId, item.id, file), 1.hours)
        val t = item.localTime
        val stamp = "${t.year}${pad(t.month.number)}${pad(t.day)}-${pad(t.hour)}${pad(t.minute)}${pad(t.second)}"
        return SavedFile(url, "postmarked-$stamp-${item.id.take(8)}", item.kind)
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    /** The size in bytes of the item's main file (the original photo, or the video), for the info panel. */
    suspend fun fileSize(tripId: String, item: TripMedia): Long? =
        bucket.info(path(tripId, item.id, if (item.kind == MediaKind.Video) MediaKind.VIDEO else MediaKind.ORIGINAL)).size

    /** Removes one of your own items: the row first, so the gallery never shows an item whose files are gone. */
    suspend fun delete(tripId: String, item: TripMedia) {
        supabase.from("media").delete { filter { eq("id", item.id) } }
        deleteFiles(item.kind.files.map { path(tripId, item.id, it) })
    }

    /**
     * Works out palettes for items uploaded before palettes existed, from their thumbnails, four
     * at a time. Each is saved for everyone; for a viewer the server quietly refuses the save
     * (only owners and editors may), and the palette is used on this phone only. Items that
     * fail are left out. Returns the filled-in items by id.
     */
    suspend fun fillPalettes(tripId: String, items: List<TripMedia>): Map<String, TripMedia> = coroutineScope {
        items.chunked(4).flatMap { chunk ->
            chunk.map { item ->
                async {
                    try {
                        val image = bucket.downloadAuthenticated(path(tripId, item.id, MediaKind.THUMB)).decodeToImageBitmap()
                        val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
                        val entries = palette(pixels).toEntries()
                        supabase.from("media").update(buildJsonObject { put("palette", Json.encodeToJsonElement(entries)) }) {
                            filter { eq("id", item.id) }
                        }
                        item.copy(palette = entries)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // e.g. its thumbnail is missing; it simply stays out of colour features
                    }
                }
            }.awaitAll().filterNotNull()
        }.associateBy { it.id }
    }

    /** Links to the picture each summary tile shows: the display copy, or a video's poster. */
    suspend fun pictureUrls(tripId: String, items: List<TripMedia>): Map<String, String> {
        val (videos, photos) = items.partition { it.kind == MediaKind.Video }
        return signedUrls(tripId, photos.map { it.id }, MediaKind.DISPLAY) + signedUrls(tripId, videos.map { it.id }, MediaKind.POSTER)
    }

    /** The full-size picture a summary tile shows (a video's poster), downloaded for exporting. */
    suspend fun pictureBytes(tripId: String, item: TripMedia): ByteArray =
        bucket.downloadAuthenticated(path(tripId, item.id, if (item.kind == MediaKind.Video) MediaKind.POSTER else MediaKind.DISPLAY))

    suspend fun reactions(mediaId: String): List<Reaction> =
        supabase.from("reactions").select(Columns.list("user_id", "emoji")) { filter { eq("media_id", mediaId) } }.decodeList()

    suspend fun react(mediaId: String, emoji: String, on: Boolean) {
        val me = myUserId ?: return
        if (on) supabase.from("reactions").insert(buildJsonObject { put("media_id", mediaId); put("emoji", emoji) })
        else supabase.from("reactions").delete { filter { eq("media_id", mediaId); eq("user_id", me); eq("emoji", emoji) } }
    }

    suspend fun comments(mediaId: String): List<Comment> =
        supabase.from("comments").select(Columns.raw("id,author,body,profile:profiles!author(display_name)")) {
            filter { eq("media_id", mediaId) }
            order("created_at", Order.ASCENDING)
        }.decodeList()

    suspend fun comment(mediaId: String, body: String) {
        supabase.from("comments").insert(buildJsonObject { put("media_id", mediaId); put("body", body.trim()) })
    }

    suspend fun deleteComment(id: String) {
        supabase.from("comments").delete { filter { eq("id", id) } }
    }
}
