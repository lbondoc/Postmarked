package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

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
}
