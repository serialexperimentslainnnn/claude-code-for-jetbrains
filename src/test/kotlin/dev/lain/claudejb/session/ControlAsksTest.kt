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
        val request = lineOf(Asks.rewind("uuid-9", dryRun = true))["request"]!!.jsonObject
        assertEquals("rewind_files", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("uuid-9", request["user_message_id"]!!.jsonPrimitive.content)
        assertEquals(true, request["dry_run"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `every declared request survives a null reply`() {
        val all = listOf(Asks.CONTEXT_USAGE, Asks.USAGE, Asks.SESSION_COST, Asks.MCP_STATUS, Asks.SETTINGS, Asks.BINARY_VERSION, Asks.rewind("u", false))
        all.forEach { assertNull(it.decode(null), "${it.subtype} should decode null to null") }
    }

    @Test
    fun `a malformed payload is null, never an exception`() {
        val nonsense = buildJsonObject { put("totally", "unexpected") }
        assertNull(Asks.CONTEXT_USAGE.decode(nonsense))
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

    @Test
    fun `generateTitle sends the description and asks the binary to keep the answer`() {
        val request = lineOf(Asks.generateTitle("arregla el guard"))["request"]!!.jsonObject
        assertEquals("generate_session_title", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("arregla el guard", request["description"]!!.jsonPrimitive.content)
        assertEquals(true, request["persist"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `generateTitle reads the title out of the reply`() {
        assertEquals("Guard audit", Asks.generateTitle("x").decode(buildJsonObject { put("title", "Guard audit") }))
    }

    @Test
    fun `a blank or missing generated title is null, so a usable fallback is never replaced by an empty tab`() {
        assertNull(Asks.generateTitle("x").decode(buildJsonObject { put("title", "   ") }))
        assertNull(Asks.generateTitle("x").decode(buildJsonObject { put("nothing", "here") }))
        assertNull(Asks.generateTitle("x").decode(null))
    }

    @Test
    fun `sideQuestion is a control request, which is what makes the answer reachable at all`() {
        val request = lineOf(Asks.sideQuestion("¿cuánto llevo gastado?"))["request"]!!.jsonObject
        assertEquals("side_question", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("¿cuánto llevo gastado?", request["question"]!!.jsonPrimitive.content)
        assertNull(request["history"])
    }

    @Test
    fun `sideQuestion reads the response text`() {
        val reply = buildJsonObject {
            put("response", "Unos cuatro euros.")
            put("synthetic", false)
        }
        assertEquals("Unos cuatro euros.", Asks.sideQuestion("q").decode(reply))
    }

    @Test
    fun `a null, blank or missing side answer is null, never an empty row`() {
        assertNull(Asks.sideQuestion("q").decode(buildJsonObject { put("response", null as String?) }))
        assertNull(Asks.sideQuestion("q").decode(buildJsonObject { put("response", "") }))
        assertNull(Asks.sideQuestion("q").decode(buildJsonObject { put("synthetic", true) }))
        assertNull(Asks.sideQuestion("q").decode(null))
    }
}
