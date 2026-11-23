package io.github.lbondoc.postmarked.domain.trip

import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class LiveCandidatesTest {
    // Big Sur, roughly.
    private val area = TripArea(listOf(Json.parseToJsonElement(
        """{"type": "Polygon", "coordinates": [[[-122, 36], [-121.5, 36], [-121.5, 36.5], [-122, 36.5], [-122, 36]]]}"""
    ).jsonObject))
    private val start = LocalDate(2026, 10, 3)
    private val end = LocalDate(2026, 10, 5)
    private val bigSur = LatLng(36.27, -121.81)
    private val newYork = LatLng(40.71, -74.0)

    private fun item(uri: String, addedMs: Long = 2_000, day: Int = 4, at: LatLng? = bigSur) =
        LiveItem(uri, addedMs, LocalDate(2026, 10, day), at)

    private fun candidates(items: List<LiveItem>, queued: Set<String> = emptySet()) =
        liveCandidates(items, sinceMs = 1_000, queuedUris = queued, start = start, end = end, area = area).map { it.uri }

    @Test
    fun keepsNewItemsTakenInTheTripsDatesAndArea() {
        assertEquals(listOf("taken-here"), candidates(listOf(item("taken-here"))))
    }

    @Test
    fun skipsItemsOutsideTheDatesOrTheArea() {
        val items = listOf(
            item("before", day = 2),
            item("after", day = 6),
            item("elsewhere", at = newYork),
            item("ok"),
        )
        assertEquals(listOf("ok"), candidates(items))
    }

    @Test
    fun skipsItemsAlreadyQueued() {
        assertEquals(listOf("b"), candidates(listOf(item("a"), item("b")), queued = setOf("a")))
    }

    @Test
    fun skipsItemsWithNoLocation() {
        assertEquals(emptyList(), candidates(listOf(item("no-gps", at = null))))
    }

    @Test
    fun onlyLooksAtWhatWasAddedSinceTheLastRun() {
        assertEquals(listOf("new"), candidates(listOf(item("old", addedMs = 1_000), item("new", addedMs = 1_001))))
    }
}
