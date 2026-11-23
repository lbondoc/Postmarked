package io.github.lbondoc.postmarked.data

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class SaveEachTest {
    /** Runs [block] to the end right here; it never suspends, so no test dispatcher is needed. */
    private fun <T> now(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    @Test
    fun reportsAfterEachAndCarriesOnPastAFailure() {
        val seen = mutableListOf<SaveProgress>()
        val saved = mutableListOf<Int>()
        val result = now { saveEach(listOf(1, 2, 3), { if (it == 2) error("missing") else saved += it }, { seen += it }) }
        assertEquals(listOf(1, 3), saved)
        assertEquals(SaveProgress(3, 3, failed = 1), result)
        assertEquals(listOf(0, 1, 2, 3), seen.map { it.done })
        assertTrue(result.finished)
    }

    @Test
    fun cancellingStopsBeforeTheNextItem() {
        val saved = mutableListOf<Int>()
        // Unconfined runs straight away: item 1 saves, then item 2 waits until cancelled.
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            saveEach(listOf(1, 2, 3), { if (it == 2) awaitCancellation() else saved += it }, {})
        }
        job.cancel()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1), saved, "item 3 never started")
    }

    @Test
    fun extensionsFollowTheFileType() {
        assertEquals(".jpg", extensionFor("image/jpeg"))
        assertEquals(".heic", extensionFor("image/heic"))
        assertEquals(".mov", extensionFor("video/quicktime"))
        assertEquals(".mp4", extensionFor("video/mp4; codecs=avc1"))
    }
}
