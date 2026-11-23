package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonObject

class StartTickedTest {
    private fun item(uri: String) = DeviceMedia(uri, MediaKind.Photo, 0, LocalDate(2026, 10, 3))
    private val found = Found(
        onTrip = listOf(item("on-trip")),
        noLocation = listOf(item("no-gps")),
        elsewhere = listOf(item("elsewhere")),
        otherDays = listOf(item("copied-later"), item("added-before")),
        locationAllowed = true,
        otherDates = listOf(item("edited-copy")),
    )

    @Test
    fun aPhoneScanTicksOnlyClearMatches() =
        assertEquals(listOf("on-trip"), startTicked(found, emptySet(), pickedByHand = false))

    @Test
    fun filesPickedByHandAllStartTicked() = assertEquals(
        listOf("on-trip", "no-gps", "elsewhere", "copied-later", "edited-copy"),
        startTicked(found, alreadyAdded = setOf("added-before"), pickedByHand = true),
    )

    @Test
    fun photosOutsideTheTripsDatesAndPlacesAreKeptNotDropped() {
        val area = io.github.lbondoc.postmarked.domain.trip.TripArea(listOf(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type": "Polygon", "coordinates": [[[-122, 36], [-121.5, 36], [-121.5, 36.5], [-122, 36.5], [-122, 36]]]}"""
        ).jsonObject))
        val trip = io.github.lbondoc.postmarked.data.TripBounds(LocalDate(2026, 10, 3), LocalDate(2026, 10, 5), area)
        val edited = DeviceMedia("edited", MediaKind.Photo, 0, LocalDate(2026, 11, 20)) // a later date, no GPS
        val found = sortForTrip(io.github.lbondoc.postmarked.data.ScanResult(listOf(item("on-day"), edited), true), trip)
        assertEquals(listOf("edited"), found.otherDates.map { it.uri })
        assertEquals(listOf("on-day"), found.noLocation.map { it.uri })
        assertEquals(false, found.isEmpty)
    }
}
