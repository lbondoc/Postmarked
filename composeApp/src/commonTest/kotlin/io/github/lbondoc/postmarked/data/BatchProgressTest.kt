package io.github.lbondoc.postmarked.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class BatchProgressTest {
    private fun upload(id: String, queuedAt: Long, finished: Boolean = false, problem: String? = null) = QueuedUpload(
        mediaId = id,
        tripId = "trip",
        item = DeviceMedia("content://$id", MediaKind.Photo, 0, LocalDate(2026, 10, 3)),
        finished = finished,
        problem = problem,
        queuedAtMs = queuedAt,
    )

    @Test
    fun nothingWaitingMeansNoBar() {
        assertNull(batchProgress(emptyList(), emptyMap()))
        assertNull(batchProgress(listOf(upload("a", 1, finished = true)), emptyMap()))
    }

    @Test
    fun countsOnlyTheCurrentBatch() {
        val uploads = listOf(
            upload("yesterday", 1, finished = true), // an earlier batch: not counted
            upload("a", 10, finished = true),
            upload("b", 10),
            upload("c", 10),
            upload("added-later", 20), // picked while the batch was running: joins it
        )
        val progress = batchProgress(uploads, emptyMap())!!
        assertEquals(1, progress.done)
        assertEquals(4, progress.total)
        assertEquals(0.25f, progress.fraction)
    }

    @Test
    fun itemsHalfwayThroughMoveTheBar() {
        val uploads = listOf(upload("a", 10, finished = true), upload("b", 10), upload("c", 10), upload("d", 10))
        val progress = batchProgress(uploads, mapOf("b" to 0.5f, "c" to 0.5f, "gone" to 1f))!!
        assertEquals(1, progress.done)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun itemsThatFailedForGoodLeaveTheCount() {
        val uploads = listOf(upload("a", 10, finished = true), upload("b", 10, problem = "too big"), upload("c", 10))
        val progress = batchProgress(uploads, emptyMap())!!
        assertEquals(2, progress.total)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun cancellingWaitingItemsShrinksTheBatch() {
        val uploads = listOf(upload("a", 10, finished = true), upload("b", 10), upload("c", 10), upload("d", 10))
        val (kept, cancelled) = cancelUploads(uploads, setOf("c", "d"))
        assertEquals(listOf("c", "d"), cancelled.map { it.mediaId })
        val progress = batchProgress(kept, emptyMap())!!
        assertEquals(1, progress.done)
        assertEquals(2, progress.total)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun cancellingAnItemMidUploadDropsItsPartialProgress() {
        val uploads = listOf(upload("a", 10, finished = true), upload("b", 10), upload("c", 10))
        val (kept, _) = cancelUploads(uploads, setOf("b"))
        // b was halfway; once cancelled its half no longer moves the bar.
        val progress = batchProgress(kept, mapOf("b" to 0.5f))!!
        assertEquals(2, progress.total)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun finishedItemsStayWhenCancellingAll() {
        val uploads = listOf(upload("a", 10, finished = true), upload("b", 10), upload("c", 10, problem = "too big"))
        val (kept, cancelled) = cancelUploads(uploads, uploads.map { it.mediaId }.toSet())
        assertEquals(listOf("a"), kept.map { it.mediaId })
        assertEquals(listOf("b", "c"), cancelled.map { it.mediaId })
        assertNull(batchProgress(kept, emptyMap()), "nothing left waiting: the bar goes")
    }
}
