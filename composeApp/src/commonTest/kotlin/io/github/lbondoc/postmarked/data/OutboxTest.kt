package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

class OutboxTest {
    private fun <T> now(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }

    private fun comment(id: String) = OutboxEntry.Comment(id, "m1", "comment $id")
    private val offline = HttpRequestException("no network", HttpRequestBuilder())

    @Test
    fun orderIsKept() {
        val queue = listOf("1", "2", "3").fold(emptyList<OutboxEntry>()) { q, id -> enqueue(q, comment(id)) }
        val sent = mutableListOf<String>()
        assertEquals(emptyList(), now { sendInOrder(queue, { sent += it.id }) })
        assertEquals(listOf("1", "2", "3"), sent)
    }

    @Test
    fun aFailedSendStaysQueuedWithEverythingAfterIt() {
        val queue = listOf(comment("1"), comment("2"), comment("3"))
        val sent = mutableListOf<String>()
        val left = now { sendInOrder(queue, { if (it.id == "2") throw offline else sent += it.id }) }
        assertEquals(listOf("1"), sent, "3 waits behind 2, so comments never arrive out of order")
        assertEquals(listOf("2", "3"), left.map { it.id })
        // A refusal that can never succeed is dropped instead of blocking the rest.
        val afterRefusal = now { sendInOrder(left, { if (it.id == "2") error("not allowed") else sent += it.id }) }
        assertEquals(emptyList(), afterRefusal)
        assertEquals(listOf("1", "3"), sent)
    }

    @Test
    fun aDuplicateReactionBecomesOne() {
        var queue = emptyList<OutboxEntry>()
        queue = enqueue(queue, OutboxEntry.Reaction("a", "m1", "❤️", on = true))
        queue = enqueue(queue, OutboxEntry.Reaction("b", "m1", "❤️", on = true))
        queue = enqueue(queue, OutboxEntry.Reaction("c", "m1", "🔥", on = true))
        assertEquals(listOf("b", "c"), queue.map { it.id })
        // Tapping again to take it back leaves one change: the last.
        queue = enqueue(queue, OutboxEntry.Reaction("d", "m1", "❤️", on = false))
        assertEquals(listOf("c", "d"), queue.map { it.id })
        assertEquals(false, (queue.last() as OutboxEntry.Reaction).on)
    }

    @Test
    fun aNewerNoteBeatsAnOlderOne() {
        val day = LocalDate(2026, 10, 3)
        fun note(id: String, at: Long) = OutboxEntry.DayNote(id, "t1", day, "note $id", updatedAtMs = at)
        var queue = enqueue(emptyList(), note("old", at = 100))
        queue = enqueue(queue, note("new", at = 200))
        assertEquals(listOf("new"), queue.map { it.id })
        // An older edit arriving late (e.g. from a slow sync) doesn't replace the newer one.
        queue = enqueue(queue, note("stale", at = 150))
        assertEquals(listOf("new"), queue.map { it.id })
        // Other days are separate.
        queue = enqueue(queue, OutboxEntry.DayNote("next", "t1", LocalDate(2026, 10, 4), "x", 50))
        assertEquals(listOf("new", "next"), queue.map { it.id })
    }
}
