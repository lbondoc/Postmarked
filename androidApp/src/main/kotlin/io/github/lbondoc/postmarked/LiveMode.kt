package io.github.lbondoc.postmarked

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.LiveMode
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.Trip
import io.github.lbondoc.postmarked.data.Trips
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.data.supabase
import io.github.lbondoc.postmarked.domain.trip.LiveItem
import io.github.lbondoc.postmarked.domain.trip.liveCandidates
import io.github.lbondoc.postmarked.domain.trip.liveModeOn
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** A trip live mode knows about: enough to decide, in the background, whether it is on and what is new. */
@Serializable
private data class LiveTrip(
    val id: String,
    val name: String,
    val start: LocalDate,
    val end: LocalDate,
    val canEdit: Boolean,
    /** The switch, when the person has touched it; null follows the default. */
    val choice: Boolean? = null,
    /** Camera-roll items added up to here have been looked at (MediaStore's date added, in ms). */
    val sinceMs: Long,
) {
    fun isOn(today: LocalDate) = liveModeOn(choice, canEdit, today, start, end)
}

/** The watched trips, kept on this phone (SharedPreferences, as the Wi-Fi setting is). */
private object LiveTrips {
    private val json = Json { ignoreUnknownKeys = true }
    val choices = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private fun prefs(context: Context) = context.getSharedPreferences("live", Context.MODE_PRIVATE)

    fun all(context: Context): List<LiveTrip> =
        runCatching { json.decodeFromString<List<LiveTrip>>(prefs(context).getString("trips", null) ?: "[]") }.getOrDefault(emptyList())

    fun save(context: Context, trips: List<LiveTrip>) {
        prefs(context).edit { putString("trips", json.encodeToString(trips)) }
        choices.value = trips.mapNotNull { t -> t.choice?.let { t.id to it } }.toMap()
    }

    fun update(context: Context, id: String, change: (LiveTrip?) -> LiveTrip?) {
        val trips = all(context)
        val next = change(trips.firstOrNull { it.id == id })
        save(context, trips.filterNot { it.id == id } + listOfNotNull(next))
    }
}

private fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault())

class AndroidLiveMode(private val context: Context) : LiveMode {
    init {
        LiveTrips.choices.value = LiveTrips.all(context).mapNotNull { t -> t.choice?.let { t.id to it } }.toMap()
    }

    override val choices: StateFlow<Map<String, Boolean>> = LiveTrips.choices

    override fun watch(trip: Trip) {
        LiveTrips.update(context, trip.id) { known ->
            // First seen now: only photos taken from here on count, never the whole camera roll.
            (known ?: LiveTrip(trip.id, trip.name, trip.startDate, trip.endDate, false, sinceMs = System.currentTimeMillis()))
                .copy(name = trip.name, start = trip.startDate, end = trip.endDate, canEdit = trip.myRole?.canEdit == true)
        }
        LiveWork.schedule(context)
    }

    override fun set(trip: Trip, on: Boolean) {
        watch(trip)
        LiveTrips.update(context, trip.id) { it?.copy(choice = on, sinceMs = if (on) System.currentTimeMillis() else it.sinceMs) }
        LiveWork.schedule(context)
    }

    /** Signing out: these trips belong to the account that opened them. */
    fun clear() {
        LiveTrips.save(context, emptyList())
        WorkManager.getInstance(context).cancelUniqueWork(LiveWork.NAME)
    }
}

object LiveWork {
    const val NAME = "live"

    /**
     * Wakes [LiveWorker] when a photo or video is added to the camera roll. A content trigger fires once, so the
     * worker re-arms it after each run. Nothing is scheduled while no trip has live mode on.
     */
    fun schedule(context: Context, afterRun: Boolean = false) {
        if (LiveTrips.all(context).none { it.isOn(today()) }) return
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5)) // wait for a burst to finish
            .setTriggerContentMaxDelay(Duration.ofSeconds(30)) // but never longer: "within a minute"
            .build()
        val request = OneTimeWorkRequestBuilder<LiveWorker>().setConstraints(constraints).build()
        // From the app, KEEP an armed one; from the worker itself, queue the next behind the one running.
        val policy = if (afterRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
    }
}

/** Queues new camera-roll items taken on a live trip, then says so in a notification. */
class LiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        supabase.auth.awaitInitialization()
        if (myUserId == null) return Result.success() // signed out: nothing to add to
        val today = today()
        for (trip in LiveTrips.all(applicationContext).filter { it.isOn(today) }) {
            val items = try {
                newItems(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.Photo, trip.sinceMs) +
                    newItems(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.Video, trip.sinceMs)
            } catch (_: SecurityException) {
                return Result.success() // photo access was taken away: live mode can't look
            }
            if (items.isEmpty()) continue
            // The trip's dates and area; saved for offline use (O3), so this works with no signal too.
            val bounds = runCatching { Trips.bounds(trip.id) }.getOrNull() ?: continue
            val queued = UploadQueue.items.value.filter { it.tripId == trip.id }.map { it.item.uri }.toSet()
            val live = items.map { (item, addedMs) -> LiveItem(item.uri, addedMs, item.takenOn, item.location) }
            val picks = liveCandidates(live, trip.sinceMs, queued, bounds.start, bounds.end, bounds.area).map { it.uri }.toSet()
            val chosen = items.map { it.first }.filter { it.uri in picks }
            if (chosen.isNotEmpty()) {
                UploadQueue.add(applicationContext, trip.id, chosen) // the Wi-Fi-only setting applies as usual
                notifyAdded(trip, chosen.size)
            }
            LiveTrips.update(applicationContext, trip.id) { it?.copy(sinceMs = maxOf(it.sinceMs, items.maxOf { (_, added) -> added })) }
        }
        LiveWork.schedule(applicationContext, afterRun = true)
        return Result.success()
    }

    /** Items added after [sinceMs], with when they were added. */
    private fun newItems(collection: Uri, kind: MediaKind, sinceMs: Long): List<Pair<DeviceMedia, Long>> {
        val found = mutableListOf<Pair<DeviceMedia, Long>>()
        applicationContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.DATE_ADDED} > ?",
            arrayOf((sinceMs / 1000).toString()), // DATE_ADDED is in seconds
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                val addedMs = cursor.getLong(2) * 1000
                val takenMs = if (cursor.isNull(1)) addedMs else cursor.getLong(1)
                found += applicationContext.readDeviceMedia(uri, kind, takenMs) to addedMs
            }
        }
        return found
    }

    /** "Added 4 photos to Big Sur · Review", opening the trip page, where the queue can be checked or cancelled. */
    private fun notifyAdded(trip: LiveTrip, count: Int) {
        val notifications = NotificationManagerCompat.from(applicationContext)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Live trips").build()
        )
        val open = PendingIntent.getActivity(
            applicationContext, trip.id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.OPEN_TRIP, trip.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (count == 1) "1 photo" else "$count photos"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Added $what to ${trip.name}")
            .setContentText("Review")
            .setContentIntent(open)
            .addAction(0, "Review", open)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(trip.id.hashCode(), notification) } // SecurityException if permission was withdrawn
    }

    private companion object {
        const val CHANNEL_ID = "live"
    }
}
