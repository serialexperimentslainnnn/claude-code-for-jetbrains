package dev.lain.claudejb.controller.session.control

import dev.lain.claudejb.controller.session.turn.QuotaWarnings
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.protocol.ClaudeJson
import dev.lain.claudejb.model.protocol.models.UsageReport
import dev.lain.claudejb.util.PluginLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionQueriesTest {

    private val sent = ArrayList<String>()
    private val edtRuns = ArrayList<() -> Unit>()
    private val notices = ArrayList<String>()
    private var running = true
    private var nextId = 0

    private val client = SessionControlClient(
        write = { sent += it },
        newRequestId = { "req_${++nextId}" },
        scheduler = { _, _ -> SessionControlClient.Cancellable { } },
    )

    private val queries = SessionQueries(
        controlClient = client,
        isRunning = { running },
        edt = { edtRuns += it },
        write = { sent += it },
        quota = QuotaWarnings(PluginLog.of(SessionQueriesTest::class), QuotaWarnings.Announce({ notices += it }, { notices += it })),
    )

    private fun lastRequest(): JsonObject = ClaudeJson.parseToJsonElement(sent.last()).jsonObject["request"]!!.jsonObject

    private fun answer(id: String, payload: JsonObject?, ok: Boolean = true, error: String? = null) {
        client.onControlResult(ClaudeEvent.ControlResult(id, success = ok, payload = payload, error = error))
        edtRuns.toList().forEach { it() }
        edtRuns.clear()
    }

    @Test
    fun `a stopped session answers every ask with nothing, on the EDT, without writing`() {
        running = false
        var got: JsonObject? = buildJsonObject { put("x", 1) }
        queries.requestMcpStatus { got = it }
        assertTrue(sent.isEmpty())
        assertEquals(1, edtRuns.size)
        edtRuns.single()()
        assertNull(got)
        queries.reconnectMcp("code")
        queries.stopTask("t")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `each query writes its subtype and hands the decoded reply back on the EDT`() {
        var cost: JsonObject? = null
        queries.requestSessionCost { cost = it }
        assertEquals("get_session_cost", lastRequest()["subtype"]!!.jsonPrimitive.content)
        answer("req_1", buildJsonObject { put("total", 1) })
        assertEquals(1, cost!!["total"]!!.jsonPrimitive.content.toInt())

        var plan: PlanInfo? = null
        queries.requestPlan { plan = it }
        answer(
            "req_2",
            buildJsonObject {
                put("exists", true)
                put("content", "# plan")
            },
        )
        assertEquals(PlanInfo(true, "# plan"), plan)

        var title: String? = null
        queries.requestGeneratedTitle("fix the bug") { title = it }
        assertTrue(sent.last().contains("fix the bug"))
        answer("req_3", buildJsonObject { put("title", "Fix the bug") })
        assertEquals("Fix the bug", title)

        var rewind: RewindResult? = null
        queries.requestRewindFiles("m1", dryRun = true) { rewind = it }
        assertTrue(sent.last().contains("m1"))
        answer("req_4", buildJsonObject { put("canRewind", true) })
        assertEquals(true, rewind?.canRewind)
    }

    @Test
    fun `the usage query feeds the quota warnings before answering`() {
        var report: UsageReport? = null
        queries.requestUsage { report = it }
        val payload = ClaudeJson.parseToJsonElement(
            """{"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":93.0}}}""",
        ).jsonObject
        answer("req_1", payload)
        assertTrue(report != null && !report!!.isEmpty)
        assertTrue(notices.any { it.contains("93%") }, notices.toString())
    }

    @Test
    fun `remote control reports the outcome, the session url when the binary gives one, and a stopped session`() {
        val outcomes = ArrayList<RemoteControlOutcome>()
        queries.setRemoteControl(true) { outcomes += it }
        assertEquals("remote_control", lastRequest()["subtype"]!!.jsonPrimitive.content)
        answer("req_1", buildJsonObject { put("url", "https://claude.ai/code/session/abc") })
        assertEquals(RemoteControlOutcome(true, true, "https://claude.ai/code/session/abc", null), outcomes.single())

        queries.setRemoteControl(false) { outcomes += it }
        answer("req_2", null, ok = false, error = "not enabled")
        assertEquals(RemoteControlOutcome(false, false, null, "not enabled"), outcomes.last())

        running = false
        queries.setRemoteControl(true) { outcomes += it }
        edtRuns.single()()
        assertFalse(outcomes.last().ok)
        assertEquals("the session is not running", outcomes.last().error)
    }

    @Test
    fun `the fire-and-forget requests are written once each and carry their subject`() {
        queries.reconnectMcp("code")
        queries.toggleMcp("vcs", false)
        queries.stopTask("task-1")
        queries.seedReadState("/p/A.kt", 42)
        assertEquals(4, sent.size)
        assertTrue(sent[0].contains("code") && sent[1].contains("vcs") && sent[2].contains("task-1") && sent[3].contains("A.kt"))
    }
}
