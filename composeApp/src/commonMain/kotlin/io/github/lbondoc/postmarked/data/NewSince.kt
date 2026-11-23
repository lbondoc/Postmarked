package io.github.lbondoc.postmarked.data

import kotlin.time.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * How many of [media] others added after [lastOpenedMs] (SPEC §5, "N new from friends"). Your own uploads never
 * count. A trip never opened on this device has nothing to compare with, so it counts none.
 */
fun newFromFriends(media: List<TripMedia>, lastOpenedMs: Long?, me: String?): Int {
    if (lastOpenedMs == null) return 0
    return media.count { item ->
        item.contributor != me && item.createdAt?.let { Instant.parse(it).toEpochMilliseconds() > lastOpenedMs } == true
    }
}

/** When each trip was last opened on this device, kept with the offline copies (and forgotten on sign-out). */
class LastOpened(private val cache: TripCache) {
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    fun of(tripId: String): Long? = all()[tripId]

    fun all(): Map<String, Long> = cache.get(KEY, serializer).orEmpty()

    fun opened(tripId: String, atMs: Long) = cache.put(KEY, serializer, all() + (tripId to atMs))

    private companion object {
        const val KEY = "last-opened"
    }
}
