package dev.lain.claudejb.controller.session.control

import dev.lain.claudejb.controller.session.turn.QuotaWarnings
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.TranscriptModel
import dev.lain.claudejb.util.PluginLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteControlTest {

    private val edtRuns = ArrayList<() -> Unit>()
    private var running = true
    private var nextId = 0
    private var stateFired = 0
    private var settled = 0
    private val transcript = TranscriptModel()

    private val client = SessionControlClient(
        write = { },
        newRequestId = { "req_${++nextId}" },
        scheduler = { _, _ -> SessionControlClient.Cancellable { } },
    )

    private val queries = SessionQueries(
        controlClient = client,
        isRunning = { running },
        edt = { edtRuns += it },
        write = { },
        quota = QuotaWarnings(PluginLog.of(RemoteControlTest::class), QuotaWarnings.Announce({ }, { })),
    )

    private val control = RemoteControl(queries, transcript) { stateFired++ }

    private fun answer(id: String, payload: JsonObject?, ok: Boolean = true, error: String? = null) {
        client.onControlResult(ClaudeEvent.ControlResult(id, success = ok, payload = payload, error = error))
        edtRuns.toList().forEach { it() }
        edtRuns.clear()
    }

    private fun lastNotice(): String = transcript.entries.last { it.speaker == Speaker.SYSTEM }.text

    @Test
    fun `switching on records the state, clears the error and announces the session url`() {
        control.set(true) { settled++ }
        answer("req_1", buildJsonObject { put("url", "https://claude.ai/code/s/1") })
        assertTrue(control.enabled)
        assertNull(control.error)
        assertEquals(1, stateFired)
        assertEquals(1, settled)
        assertEquals("Remote Control is on — https://claude.ai/code/s/1", lastNotice())
    }

    @Test
    fun `switching on without a url points at the sessions page, and switching off says the chat stays here`() {
        control.set(true) { }
        answer("req_1", buildJsonObject { put("ok", true) })
        assertTrue(lastNotice().contains("https://claude.ai/code"))
        control.set(false) { }
        answer("req_2", null)
        assertFalse(control.enabled)
        assertEquals("Remote Control is off. This chat keeps running in the IDE.", lastNotice())
    }

    @Test
    fun `a refusal keeps the previous state and names the reason, with and without one`() {
        control.set(true) { }
        answer("req_1", null, ok = false, error = "not enabled for this account")
        assertFalse(control.enabled)
        assertEquals("Could not switch Remote Control on: not enabled for this account", control.error)
        assertTrue(lastNotice().startsWith(control.error!!) && lastNotice().contains("Team and Enterprise"))
        control.set(false) { }
        answer("req_2", null, ok = false, error = " ")
        assertEquals("Could not switch Remote Control off.", control.error)
    }

    @Test
    fun `a stopped session is a refusal too`() {
        running = false
        control.set(true) { settled++ }
        edtRuns.toList().forEach { it() }
        assertFalse(control.enabled)
        assertEquals("Could not switch Remote Control on: the session is not running", control.error)
        assertEquals(1, settled)
    }
}
