package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ControlProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The declared control requests ([Asks]) and the generic line they turn into.
 *
 * Each of these used to be written out three times — a builder, a method repeating six lines of plumbing,
 * and a decode expression in the middle of it. Declaring them is only an improvement if the declaration
 * still produces exactly the line the binary expects, which is what this checks: the wire format, the
 * field names, and that a decoder answers null rather than throwing when the reply is missing or malformed.
 */
class ControlAsksTest {

    private fun lineOf(ask: Ask<*>) =
        Json.parseToJsonElement(ControlProtocol.of("req-1", ask.subtype, ask.params)).jsonObject

    @Test
    fun `a declared request is the same control_request line as a hand-written one`() {
        val line = lineOf(Asks.USAGE)
        assertEquals("control_request", line["type"]!!.jsonPrimitive.content)
        assertEquals("req-1", line["request_id"]!!.jsonPrimitive.content)
        assertEquals("get_usage", line["request"]!!.jsonObject["subtype"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewind sends the field names the binary accepts`() {
        // SNAKE_CASE on the way out. The reply has been seen in both spellings, but the REQUEST is not
        // symmetric with it — sending camelCase means the binary rewinds nothing and says so politely.
        val request = lineOf(Asks.rewind("uuid-9", dryRun = true))["request"]!!.jsonObject
        assertEquals("rewind_files", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("uuid-9", request["user_message_id"]!!.jsonPrimitive.content)
        assertEquals(true, request["dry_run"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `every declared request survives a null reply`() {
        // null is what a refusal and a timed-out watchdog both look like. A decoder that throws there takes
        // the callback with it, and the caller waits for ever on a spinner.
        val all = listOf(Asks.CONTEXT_USAGE, Asks.USAGE, Asks.SESSION_COST, Asks.MCP_STATUS, Asks.SETTINGS, Asks.BINARY_VERSION, Asks.rewind("u", false))
        all.forEach { assertNull(it.decode(null), "${it.subtype} should decode null to null") }
    }

    @Test
    fun `a malformed payload is null, never an exception`() {
        val nonsense = buildJsonObject { put("totally", "unexpected") }
        assertNull(Asks.CONTEXT_USAGE.decode(nonsense))
        // The passthrough ones hand the object back as-is — that IS their answer.
        assertEquals(nonsense, Asks.SESSION_COST.decode(nonsense))
    }

    @Test
    fun `rewind reads either spelling of the reply`() {
        val camel = buildJsonObject {
            put("canRewind", true)
            put("filesChanged", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a.kt")) })
        }
        val snake = buildJsonObject {
            put("can_rewind", true)
            put("files_changed", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a.kt")) })
        }
        listOf(camel, snake).forEach {
            val result = Asks.rewind("u", false).decode(it)!!
            assertTrue(result.canRewind)
            assertEquals(listOf("a.kt"), result.filesChanged)
        }
    }
}
