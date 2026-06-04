package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [ProtocolParser.parse] for the two binary->host control requests added in 3.3.0:
 * `request_user_dialog` (→ [ClaudeEvent.UserDialogRequest], answered cancelled) and `elicitation`
 * (→ [ClaudeEvent.Elicitation], surfaced as a card). A malformed elicitation frame must still degrade to an
 * answerable [ClaudeEvent.UnsupportedControlRequest] rather than throwing in the reader loop.
 */
class ProtocolParserControlTest {

    private fun parseSingle(line: String): ClaudeEvent = ProtocolParser.parse(line).single()

    @Test
    fun `request_user_dialog maps kind, payload and tool_use_id`() {
        val line = """
            {"type":"control_request","request_id":"r1",
             "request":{"subtype":"request_user_dialog","dialog_kind":"confirm",
                        "tool_use_id":"tu1","payload":{"a":1}}}
        """.trimIndent().replace("\n", "")
        val e = parseSingle(line) as ClaudeEvent.UserDialogRequest
        assertEquals("r1", e.requestId)
        assertEquals("confirm", e.dialogKind)
        assertEquals("tu1", e.toolUseId)
        assertTrue(e.payload.containsKey("a"))
    }

    @Test
    fun `request_user_dialog tolerates absent kind and payload`() {
        val line = """{"type":"control_request","request_id":"r2","request":{"subtype":"request_user_dialog"}}"""
        val e = parseSingle(line) as ClaudeEvent.UserDialogRequest
        assertNull(e.dialogKind)
        assertNull(e.toolUseId)
        assertTrue(e.payload.isEmpty())
    }

    @Test
    fun `elicitation maps server, message, mode, url and schema`() {
        val line = """
            {"type":"control_request","request_id":"r3",
             "request":{"subtype":"elicitation","mcp_server_name":"github","message":"Authorize?",
                        "mode":"url","url":"https://example/auth","title":"GitHub","description":"desc",
                        "requested_schema":{"properties":{"name":{"type":"string"}}}}}
        """.trimIndent().replace("\n", "")
        val e = parseSingle(line) as ClaudeEvent.Elicitation
        assertEquals("r3", e.requestId)
        assertEquals("github", e.request.mcpServerName)
        assertEquals("Authorize?", e.request.message)
        assertEquals("url", e.request.mode)
        assertEquals("https://example/auth", e.request.url)
        assertEquals("GitHub", e.request.title)
        assertEquals("desc", e.request.description)
        assertEquals(1, parseElicitationFields(e.request.requestedSchema).size)
    }

    @Test
    fun `malformed elicitation degrades to an answerable unsupported request`() {
        // requested_schema typed as an array (not object) makes the strict decode fail → fallback path.
        val line = """
            {"type":"control_request","request_id":"r4",
             "request":{"subtype":"elicitation","mcp_server_name":"x","requested_schema":[1,2,3]}}
        """.trimIndent().replace("\n", "")
        val e = parseSingle(line) as ClaudeEvent.UnsupportedControlRequest
        assertEquals("r4", e.requestId)
        assertEquals("elicitation", e.subtype)
    }
}
