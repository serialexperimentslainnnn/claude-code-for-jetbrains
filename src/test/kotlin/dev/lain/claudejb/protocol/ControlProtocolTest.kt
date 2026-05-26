package dev.lain.claudejb.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the exact wire shape of every line the plugin writes to the binary's stdin. These are built
 * explicitly (not via the lenient [ClaudeJson]) precisely because the binary's runtime schema is stricter
 * than the published .d.ts (e.g. `updatedInput` is required), so the shapes must not drift silently.
 */
class ControlProtocolTest {

    private fun parse(line: String): JsonObject =
        ClaudeJson.parseToJsonElement(line) as JsonObject

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `userMessage emits explicit null parent_tool_use_id`() {
        val root = parse(ControlProtocol.userMessage("hello"))
        assertEquals("user", root.string("type"))
        assertEquals("hello", root.obj("message").string("content"))
        assertEquals("user", root.obj("message").string("role"))
        // The protocol expects the key present and explicitly null, not omitted.
        assertEquals(JsonNull, root["parent_tool_use_id"])
    }

    @Test
    fun `userMessage carries parent_tool_use_id when provided`() {
        val root = parse(ControlProtocol.userMessage("hi", parentToolUseId = "p1"))
        assertEquals("p1", root.string("parent_tool_use_id"))
    }

    @Test
    fun `permissionAllow requires behavior allow and updatedInput`() {
        val input = buildJsonObject { put("file_path", "a.kt") }
        val root = parse(ControlProtocol.permissionAllow("r1", input))
        assertEquals("control_response", root.string("type"))
        val response = root.obj("response")
        assertEquals("success", response.string("subtype"))
        assertEquals("r1", response.string("request_id"))
        val inner = response.obj("response")
        assertEquals("allow", inner.string("behavior"))
        // updatedInput MUST be present (binary rejects the response otherwise).
        assertEquals(input, inner["updatedInput"])
    }

    @Test
    fun `permissionDeny carries behavior deny and message`() {
        val root = parse(ControlProtocol.permissionDeny("r2", "nope"))
        val inner = root.obj("response").obj("response")
        assertEquals("deny", inner.string("behavior"))
        assertEquals("nope", inner.string("message"))
    }

    @Test
    fun `error reply has error subtype and request id`() {
        val root = parse(ControlProtocol.error("r3", "bad"))
        val response = root.obj("response")
        assertEquals("error", response.string("subtype"))
        assertEquals("r3", response.string("request_id"))
        assertEquals("bad", response.string("error"))
    }

    @Test
    fun `setPermissionModeRequest wraps a control_request`() {
        val root = parse(ControlProtocol.setPermissionModeRequest("r4", "plan"))
        assertEquals("control_request", root.string("type"))
        assertEquals("r4", root.string("request_id"))
        val request = root.obj("request")
        assertEquals("set_permission_mode", request.string("subtype"))
        assertEquals("plan", request.string("mode"))
    }

    @Test
    fun `setModelRequest includes model when given`() {
        val request = parse(ControlProtocol.setModelRequest("r5", "claude-opus-4-7")).obj("request")
        assertEquals("set_model", request.string("subtype"))
        assertEquals("claude-opus-4-7", request.string("model"))
    }

    @Test
    fun `interruptRequest has interrupt subtype`() {
        val request = parse(ControlProtocol.interruptRequest("r6")).obj("request")
        assertEquals("interrupt", request.string("subtype"))
    }

    @Test
    fun `newRequestId is prefixed and unique`() {
        val a = ControlProtocol.newRequestId()
        val b = ControlProtocol.newRequestId()
        assertTrue(a.startsWith("req_"))
        assertTrue(a != b)
    }
}
