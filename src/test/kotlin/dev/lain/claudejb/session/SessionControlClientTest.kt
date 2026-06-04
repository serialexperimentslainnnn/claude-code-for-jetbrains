package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [SessionControlClient] — the control-request correlation extracted from `ClaudeSession`.
 * Uses a fake scheduler (so timeouts fire on demand, never via the real executor) and a deterministic id minter,
 * matching the project's "inject the scheduler, don't depend on the real one" guidance.
 */
class SessionControlClientTest {

    /** Captures scheduled tasks so a test can fire (or never fire) the watchdog deterministically. */
    private class FakeScheduler : SessionControlClient.Scheduler {
        val tasks = mutableListOf<() -> Unit>()
        var cancelled = 0
        override fun schedule(delaySeconds: Long, task: () -> Unit): SessionControlClient.Cancellable {
            tasks += task
            return SessionControlClient.Cancellable { cancelled++ }
        }
        fun fireAll() = tasks.toList().forEach { it() }
    }

    private fun client(
        sent: MutableList<String>,
        scheduler: SessionControlClient.Scheduler,
        ids: Iterator<String>,
    ) = SessionControlClient(
        write = { sent += it },
        newRequestId = { ids.next() },
        scheduler = scheduler,
    )

    @Test
    fun `query registers, writes the request, and resolves on the matching control result`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1").iterator())

        var captured: String? = "unset"
        client.query(
            buildRequest = { id -> "line-for-$id" },
            onResult = { v: String? -> captured = v },
            decode = { payload -> payload?.str("value") },
        )

        // The request line was written using the minted id; nothing resolved yet.
        assertEquals(listOf("line-for-req_1"), sent)
        assertEquals("unset", captured)

        // Reply for the matching id → decoded payload delivered, watchdog cancelled.
        val payload = buildJsonObject { put("value", "hello") }
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = payload, error = null))
        assertEquals("hello", captured)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun `onControlResult ignores an unknown request id`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1").iterator())

        var invoked = false
        client.query(
            buildRequest = { "line" },
            onResult = { _: String? -> invoked = true },
            decode = { it?.str("value") },
        )

        // A reply for a different id must not touch the pending handler.
        client.onControlResult(ClaudeEvent.ControlResult("req_OTHER", success = true, payload = null, error = null))
        assertFalse(invoked)

        // The real reply still resolves it afterwards (handler was not consumed).
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = null, error = null))
        assertTrue(invoked)
    }

    @Test
    fun `timeout delivers onResult(null)`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1").iterator())

        var captured: JsonObject? = buildJsonObject { put("x", 1) }
        var calls = 0
        client.query(
            buildRequest = { "line" },
            onResult = { v: JsonObject? -> captured = v; calls++ },
            decode = { it }, // identity, like requestSessionCost/requestMcpStatus
        )

        scheduler.fireAll()
        assertEquals(1, calls)
        assertNull(captured)

        // A late reply after the watchdog won is a no-op (handler already removed).
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = buildJsonObject {}, error = null))
        assertEquals(1, calls)
    }

    @Test
    fun `failAll resolves all pending with null and empties the map`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1", "req_2").iterator())

        val results = mutableListOf<JsonObject?>()
        repeat(2) {
            client.query(
                buildRequest = { id -> "line-$id" },
                onResult = { v: JsonObject? -> results += v },
                decode = { it },
            )
        }

        client.failAll("process gone")
        // Both handlers fired with a null payload (failure → decode(null) → null).
        assertEquals(listOf<JsonObject?>(null, null), results)

        // The map is empty: a subsequent reply or a second failAll resolves nothing further.
        results.clear()
        client.failAll("again")
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = buildJsonObject {}, error = null))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `decode failure-payload distinguishes success from failure for non-identity decoders`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1").iterator())

        var captured: Int? = -1
        client.query(
            buildRequest = { "line" },
            onResult = { v: Int? -> captured = v },
            decode = { payload -> payload?.str("n")?.toIntOrNull() },
        )

        val payload = buildJsonObject { put("n", "42") }
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = payload, error = null))
        assertEquals(42, captured)
    }
}
