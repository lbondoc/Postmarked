package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A write made while offline (or before the server answered), waiting to be sent (SPEC §4). */
@Serializable
sealed class OutboxEntry {
    abstract val id: String

    /** Append-only: every comment is sent, in the order written. */
    @Serializable @SerialName("comment")
    data class Comment(override val id: String, val mediaId: String, val body: String) : OutboxEntry()

    /** Adding or removing one of your reactions. Sending it twice has the same effect as once. */
    @Serializable @SerialName("reaction")
    data class Reaction(override val id: String, val mediaId: String, val emoji: String, val on: Boolean) : OutboxEntry()

    /** A day's note; the newest edit wins, by [updatedAtMs]. */
    @Serializable @SerialName("note")
    data class DayNote(
        override val id: String, val tripId: String, val day: LocalDate, val body: String, val updatedAtMs: Long,
    ) : OutboxEntry()
}

/**
 * [entry] added to [queue] by the outbox's rules. Comments queue up in order. A reaction replaces any queued one
 * for the same photo and emoji, so tapping twice sends one change, the last. A note replaces a queued note for
 * the same day only if it is newer, so an older edit can never overwrite a newer one.
 */
fun enqueue(queue: List<OutboxEntry>, entry: OutboxEntry): List<OutboxEntry> = when (entry) {
    is OutboxEntry.Comment -> queue + entry
    is OutboxEntry.Reaction -> queue.filterNot { it is OutboxEntry.Reaction && it.mediaId == entry.mediaId && it.emoji == entry.emoji } + entry
    is OutboxEntry.DayNote -> {
        val older = queue.filterIsInstance<OutboxEntry.DayNote>().firstOrNull { it.tripId == entry.tripId && it.day == entry.day }
        when {
            older == null -> queue + entry
            older.updatedAtMs > entry.updatedAtMs -> queue // the queued edit is newer: keep it
            else -> queue.filterNot { it === older } + entry
        }
    }
}

/**
 * Sends [queue] in order and returns what is still waiting. A send that fails for lack of network stops the run,
 * keeping that entry and everything after it in order, to try again later. One the server refuses for good
 * ([permanent]) is dropped, so it can't block everything behind it.
 */
suspend fun sendInOrder(
    queue: List<OutboxEntry>,
    send: suspend (OutboxEntry) -> Unit,
    permanent: (Throwable) -> Boolean = { !isOffline(it) },
): List<OutboxEntry> {
    queue.forEachIndexed { i, entry ->
        try {
            send(entry)
        } catch (e: Exception) {
            if (!permanent(e)) return queue.drop(i)
        }
    }
    return emptyList()
}

/** The persisted outbox, saved in the same store as the offline copies (and forgotten with them on sign-out). */
object Outbox {
    private const val KEY = "outbox"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OutboxEntry.serializer())
    private val lock = Mutex()
    private val state by lazy {
        MutableStateFlow(runCatching { TripCache.store.read(KEY)?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty())
    }

    /** Everything waiting to send, for "Waiting to send" in the UI. */
    val entries: StateFlow<List<OutboxEntry>> get() = state.asStateFlow()

    /** Called after each add; Android uses it to schedule a send for when the network is back. */
    var onQueued: () -> Unit = {}

    @OptIn(ExperimentalUuidApi::class)
    fun newId(): String = Uuid.random().toString()

    fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /** Queues [entry] (saved first, so it survives the app closing) and tries to send straight away. */
    suspend fun add(entry: OutboxEntry) {
        lock.withLock { save(enqueue(state.value, entry)) }
        onQueued()
        flush()
    }

    /** Sends what is waiting, in order. Called on start, after successful loads, and by Android's network job. */
    suspend fun flush() = lock.withLock { save(sendInOrder(state.value, ::send)) }

    /** Signing out: these writes belong to the account that made them. */
    fun forget() {
        state.value = emptyList()
    }

    private fun save(entries: List<OutboxEntry>) {
        runCatching { TripCache.store.write(KEY, json.encodeToString(serializer, entries)) }
        state.value = entries
    }

    private suspend fun send(entry: OutboxEntry): Unit = when (entry) {
        is OutboxEntry.Comment -> Media.comment(entry.mediaId, entry.body)
        is OutboxEntry.Reaction -> try {
            Media.react(entry.mediaId, entry.emoji, entry.on)
        } catch (e: PostgrestRestException) {
            if (e.code != "23505") throw e else Unit // 23505: that reaction is already there, which is what was wanted
        }
        // A blank note removes the day's note.
        is OutboxEntry.DayNote if entry.body.isBlank() -> supabase.from("day_notes").delete {
            filter { eq("trip_id", entry.tripId); eq("day", entry.day.toString()) }
        }.let { }
        // Upsert by (trip, day). The database keeps whichever edit is newest (day_notes migration).
        is OutboxEntry.DayNote -> supabase.from("day_notes").upsert(buildJsonObject {
            put("trip_id", entry.tripId)
            put("day", entry.day.toString())
            put("body", entry.body)
            put("updated_at", Instant.fromEpochMilliseconds(entry.updatedAtMs).toString())
        }).let { }
    }
}
