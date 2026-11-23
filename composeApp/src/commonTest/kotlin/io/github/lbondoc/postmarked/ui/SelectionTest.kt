package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.data.TripMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun aDragCoversEverythingBetweenWhereItStartedAndTheFinger() {
        assertEquals(listOf("b", "c", "d"), dragRange(order, anchor = 1, here = 3))
        assertEquals(listOf("b", "c", "d"), dragRange(order, anchor = 3, here = 1)) // dragging back up
        assertEquals(listOf("c"), dragRange(order, anchor = 2, here = 2))
    }

    @Test
    fun aDragSelectsOrDeselectsTheRunAndKeepsTheRest() {
        assertEquals(setOf("a", "b", "c", "d"), selectRange(setOf("a"), dragRange(order, 1, 3), on = true))
        // Starting on a selected tile deselects the run; tiles outside it keep their state.
        assertEquals(setOf("a", "e"), selectRange(setOf("a", "b", "c", "e"), dragRange(order, 1, 3), on = false))
        // Dragging back shrinks the run: the selection comes from the start of the drag, not the last step.
        val base = setOf("a")
        selectRange(base, dragRange(order, 1, 4), on = true)
        assertEquals(setOf("a", "b", "c"), selectRange(base, dragRange(order, 1, 2), on = true))
    }

    @Test
    fun aTapToggles() {
        assertEquals(setOf("a", "b"), setOf("a").toggled("b"))
        assertEquals(setOf("a"), setOf("a", "b").toggled("b"))
    }

    @Test
    fun deleteOnlyCoversYourOwnAndSaysHowManyAreSkipped() {
        fun item(id: String, by: String) = TripMedia(id, MediaKind.Photo, by, "2026-10-03T18:00:00Z")
        val picked = listOf(item("1", "me"), item("2", "sam"), item("3", "me"), item("4", "maya"))
        val (mine, skipped) = ownItems(picked, "me")
        assertEquals(listOf("1", "3"), mine.map { it.id })
        assertEquals(2, skipped)
        assertEquals("2 not yours, skipped", skippedNote(skipped))
        assertNull(skippedNote(0))
        assertEquals(0 to 4, ownItems(picked, null).let { it.first.size to it.second }) // signed out: nothing is yours
    }
}
