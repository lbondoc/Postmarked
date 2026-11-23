package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
    /** Who took it. */
    val profile: Profile? = null,
) {
    val location: LatLng? get() = if (lat != null && lng != null) LatLng(lat, lng) else null
    val takenAt: Instant get() = Instant.parse(capturedAt)

    /** Clock time where it was taken when the camera recorded the time zone, else in the phone's zone. */
    val localTime: LocalDateTime
        get() = if (utcOffsetMinutes != null) (takenAt + utcOffsetMinutes.minutes).toLocalDateTime(TimeZone.UTC)
        else takenAt.toLocalDateTime(TimeZone.currentSystemDefault())

    val takenOn: LocalDate get() = localTime.date
}

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
            Columns.raw("id,kind,contributor,captured_at,utc_offset_minutes,lat,lng,dhash,sharpness,profile:profiles!contributor(display_name)")
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
    suspend fun add(upload: QueuedUpload, sha256: String, dhash: Long?, sharpness: Double?): Boolean = try {
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
        })
        true
    } catch (e: PostgrestRestException) {
        if (e.code == "23505") false else throw e // 23505: Postgres "unique violation"
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

    /** A link to the full-size picture, or to the video itself. */
    suspend fun viewUrl(tripId: String, item: TripMedia): String =
        signedUrls(tripId, listOf(item.id), if (item.kind == MediaKind.Video) MediaKind.VIDEO else MediaKind.DISPLAY).getValue(item.id)

    /** Removes one of your own items: the row first, so the gallery never shows an item whose files are gone. */
    suspend fun delete(tripId: String, item: TripMedia) {
        supabase.from("media").delete { filter { eq("id", item.id) } }
        deleteFiles(item.kind.files.map { path(tripId, item.id, it) })
    }

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
