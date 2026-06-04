package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [DialogResponder] always cancels (the host renders no custom dialog kinds) and notes the request for transparency. */
class DialogResponderTest {

    @Test
    fun `response is a cancelled control_response for the request`() {
        val root = ClaudeJson.parseToJsonElement(DialogResponder.response("r1")) as JsonObject
        val response = root["response"] as JsonObject
        assertEquals("success", (response["subtype"] as JsonPrimitive).content)
        assertEquals("r1", (response["request_id"] as JsonPrimitive).content)
        assertEquals("cancelled", ((response["response"] as JsonObject)["behavior"] as JsonPrimitive).content)
    }

    @Test
    fun `notice names the dialog kind when present`() {
        assertTrue(DialogResponder.notice("confirm").contains("\"confirm\""))
    }

    @Test
    fun `notice degrades gracefully when the kind is blank or null`() {
        assertTrue(DialogResponder.notice(null).isNotBlank())
        assertTrue(DialogResponder.notice("  ").isNotBlank())
    }
}
