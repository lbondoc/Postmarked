package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class StartTickedTest {
    private fun item(uri: String) = DeviceMedia(uri, MediaKind.Photo, 0, LocalDate(2026, 10, 3))
    private val found = Found(
        onTrip = listOf(item("on-trip")),
        noLocation = listOf(item("no-gps")),
        elsewhere = listOf(item("elsewhere")),
        otherDays = listOf(item("copied-later"), item("added-before")),
        locationAllowed = true,
    )

    @Test
    fun aPhoneScanTicksOnlyClearMatches() =
        assertEquals(listOf("on-trip"), startTicked(found, emptySet(), pickedByHand = false))

    @Test
    fun filesPickedByHandAllStartTicked() = assertEquals(
        listOf("on-trip", "no-gps", "elsewhere", "copied-later"),
        startTicked(found, alreadyAdded = setOf("added-before"), pickedByHand = true),
    )

    private fun undated(uri: String) = DeviceMedia(uri, MediaKind.Photo, 0, LocalDate(2026, 11, 20), dateKnown = false)

    @Test
    fun addStaysDisabledWhileATickedUndatedItemHasNoDay() {
        val chosen = listOf(item("on-trip"), undated("screenshot"), undated("scan"))
        assertFalse(readyToAdd(chosen, emptyMap()))
        assertFalse(readyToAdd(chosen, mapOf("screenshot" to LocalDate(2026, 10, 4))), "one still has no day")
        assertTrue(readyToAdd(chosen, mapOf("screenshot" to LocalDate(2026, 10, 4), "scan" to LocalDate(2026, 10, 3))))
        assertTrue(readyToAdd(listOf(item("on-trip")), emptyMap()), "dated items need nothing")
        assertFalse(readyToAdd(emptyList(), emptyMap()), "nothing ticked")
    }

    @Test
    fun aChosenDayWithNoTimeIsNoonInTheDeviceZoneWithNoOffset() {
        val la = TimeZone.of("America/Los_Angeles")
        val dated = withChosenDay(undated("screenshot"), LocalDate(2026, 10, 4), null, la)
        assertEquals(Instant.parse("2026-10-04T19:00:00Z").toEpochMilliseconds(), dated.capturedAtMs) // noon PDT
        assertEquals(LocalDate(2026, 10, 4), dated.takenOn)
        assertNull(dated.utcOffsetMinutes)
        assertTrue(dated.dateByHand)
        val timed = withChosenDay(undated("scan"), LocalDate(2026, 10, 4), LocalTime(8, 15), la)
        assertEquals(Instant.parse("2026-10-04T15:15:00Z").toEpochMilliseconds(), timed.capturedAtMs)
    }

    @Test
    fun undatedFilesGoInTheirOwnSectionAndStartTickedWhenPickedByHand() {
        val withUndated = Found(listOf(item("on-trip")), emptyList(), emptyList(), emptyList(), true, noDate = listOf(undated("screenshot")))
        assertEquals(listOf("on-trip", "screenshot"), startTicked(withUndated, emptySet(), pickedByHand = true))
        assertEquals(listOf("on-trip"), startTicked(withUndated, emptySet(), pickedByHand = false))
    }
}
