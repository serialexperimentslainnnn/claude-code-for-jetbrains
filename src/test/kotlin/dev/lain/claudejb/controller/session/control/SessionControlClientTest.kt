package dev.lain.claudejb.controller.session.control

import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionControlClientTest {

    private class FakeScheduler : SessionControlClient.Scheduler {
        val tasks = mutableListOf<() -> Unit>()
        var cancelled = 0
        override fun schedule(delaySeconds: Long, task: () -> Unit): SessionControlClient.Cancellable {
            tasks += task
            return SessionControlClient.Cancellable {
                cancelled++
                tasks.remove(task)
            }
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

        assertEquals(listOf("line-for-req_1"), sent)
        assertEquals("unset", captured)

        val payload = buildJsonObject { put("value", "hello") }
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = payload, error = null))
        assertEquals("hello", captured)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun `failing every pending request also cancels every watchdog`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1", "req_2").iterator())
        val errors = mutableListOf<String?>()
        repeat(2) { client.send({ id -> "line-$id" }) { errors += it.error } }

        client.failAll("process gone")

        assertEquals(listOf("process gone", "process gone"), errors)
        assertEquals(2, scheduler.cancelled)
        scheduler.fireAll()
        assertEquals(2, errors.size)
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

        client.onControlResult(ClaudeEvent.ControlResult("req_OTHER", success = true, payload = null, error = null))
        assertFalse(invoked)

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
            onResult = { v: JsonObject? ->
                captured = v
                calls++
            },
            decode = { it },
        )

        scheduler.fireAll()
        assertEquals(1, calls)
        assertNull(captured)

        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = buildJsonObject {}, error = null))
        assertEquals(1, calls)
    }

    @Test
    fun `a request the binary reports as started is long-running, so its watchdog is dropped and the late answer lands`() {
        val sent = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val client = client(sent, scheduler, listOf("req_1").iterator())

        var captured: String? = "unset"
        client.query(
            buildRequest = { "line" },
            onResult = { v: String? -> captured = v },
            decode = { payload -> payload?.str("response") },
        )

        client.onProgress("req_1")
        assertEquals(1, scheduler.cancelled)
        scheduler.fireAll()
        assertEquals("unset", captured)

        val payload = buildJsonObject { put("response", "forty-two") }
        client.onControlResult(ClaudeEvent.ControlResult("req_1", success = true, payload = payload, error = null))
        assertEquals("forty-two", captured)
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
        assertEquals(listOf<JsonObject?>(null, null), results)

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
