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
    fun `userMessageWithImages falls back to plain string content when no images`() {
        val root = parse(ControlProtocol.userMessageWithImages("just text", emptyList()))
        // No images → identical to userMessage: content is a plain string, not an array.
        assertEquals("just text", root.obj("message").string("content"))
    }

    @Test
    fun `userMessageWithImages emits a text block then one image block per image`() {
        val images = listOf("image/png" to "AAAA", "image/jpeg" to "BBBB")
        val root = parse(ControlProtocol.userMessageWithImages("describe these", images))
        val content = root.obj("message")["content"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, content.size)
        val text = content[0] as JsonObject
        assertEquals("text", text.string("type"))
        assertEquals("describe these", text.string("text"))
        val img0 = content[1] as JsonObject
        assertEquals("image", img0.string("type"))
        val src0 = img0.obj("source")
        assertEquals("base64", src0.string("type"))
        assertEquals("image/png", src0.string("media_type"))
        assertEquals("AAAA", src0.string("data"))
        assertEquals("image/jpeg", (content[2] as JsonObject).obj("source").string("media_type"))
    }

    @Test
    fun `userMessageWithImages omits the text block when the prompt is blank`() {
        val root = parse(ControlProtocol.userMessageWithImages("   ", listOf("image/png" to "AAAA")))
        val content = root.obj("message")["content"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, content.size)
        assertEquals("image", (content[0] as JsonObject).string("type"))
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

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    private fun JsonObject.num(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `renameSessionRequest carries title`() {
        val request = parse(ControlProtocol.renameSessionRequest("r10", "My session")).obj("request")
        assertEquals("rename_session", request.string("subtype"))
        assertEquals("My session", request.string("title"))
    }

    @Test
    fun `setColorRequest carries color`() {
        val request = parse(ControlProtocol.setColorRequest("r11", "blue")).obj("request")
        assertEquals("set_color", request.string("subtype"))
        assertEquals("blue", request.string("color"))
    }

    @Test
    fun `getSettingsRequest has get_settings subtype`() {
        val request = parse(ControlProtocol.getSettingsRequest("r12")).obj("request")
        assertEquals("get_settings", request.string("subtype"))
    }

    @Test
    fun `getBinaryVersionRequest has get_binary_version subtype`() {
        val request = parse(ControlProtocol.getBinaryVersionRequest("r13")).obj("request")
        assertEquals("get_binary_version", request.string("subtype"))
    }

    @Test
    fun `fileSuggestionsRequest carries query`() {
        val request = parse(ControlProtocol.fileSuggestionsRequest("r14", "src/Ma")).obj("request")
        assertEquals("file_suggestions", request.string("subtype"))
        assertEquals("src/Ma", request.string("query"))
    }

    @Test
    fun `readFileRequest carries path and omits optionals by default`() {
        val request = parse(ControlProtocol.readFileRequest("r15", "a.kt")).obj("request")
        assertEquals("read_file", request.string("subtype"))
        assertEquals("a.kt", request.string("path"))
        assertTrue(!request.containsKey("max_bytes"))
        assertTrue(!request.containsKey("encoding"))
    }

    @Test
    fun `readFileRequest carries max_bytes and encoding when given`() {
        val request = parse(ControlProtocol.readFileRequest("r16", "img.png", maxBytes = 1024, encoding = "base64")).obj("request")
        assertEquals("1024", request.num("max_bytes"))
        assertEquals("base64", request.string("encoding"))
    }

    @Test
    fun `rewindFilesRequest carries user_message_id and dry_run`() {
        val request = parse(ControlProtocol.rewindFilesRequest("r17", "msg-1", dryRun = true)).obj("request")
        assertEquals("rewind_files", request.string("subtype"))
        assertEquals("msg-1", request.string("user_message_id"))
        assertEquals(true, request.bool("dry_run"))
    }

    @Test
    fun `rewindFilesRequest omits dry_run by default`() {
        val request = parse(ControlProtocol.rewindFilesRequest("r18", "msg-2")).obj("request")
        assertTrue(!request.containsKey("dry_run"))
    }

    @Test
    fun `seedReadStateRequest carries path and mtime`() {
        val request = parse(ControlProtocol.seedReadStateRequest("r19", "a.kt", 1717000000000L)).obj("request")
        assertEquals("seed_read_state", request.string("subtype"))
        assertEquals("a.kt", request.string("path"))
        assertEquals("1717000000000", request.num("mtime"))
    }

    @Test
    fun `stopTaskRequest carries task_id`() {
        val request = parse(ControlProtocol.stopTaskRequest("r20", "task-7")).obj("request")
        assertEquals("stop_task", request.string("subtype"))
        assertEquals("task-7", request.string("task_id"))
    }

    @Test
    fun `backgroundTasksRequest carries tool_use_id when given`() {
        val request = parse(ControlProtocol.backgroundTasksRequest("r21", toolUseId = "tu-1")).obj("request")
        assertEquals("background_tasks", request.string("subtype"))
        assertEquals("tu-1", request.string("tool_use_id"))
    }

    @Test
    fun `backgroundTasksRequest omits tool_use_id by default`() {
        val request = parse(ControlProtocol.backgroundTasksRequest("r22")).obj("request")
        assertEquals("background_tasks", request.string("subtype"))
        assertTrue(!request.containsKey("tool_use_id"))
    }

    @Test
    fun `mcpReconnectRequest carries camelCase serverName`() {
        val request = parse(ControlProtocol.mcpReconnectRequest("r23", "jetbrains")).obj("request")
        assertEquals("mcp_reconnect", request.string("subtype"))
        assertEquals("jetbrains", request.string("serverName"))
    }

    @Test
    fun `mcpToggleRequest carries serverName and enabled`() {
        val request = parse(ControlProtocol.mcpToggleRequest("r24", "jetbrains", enabled = false)).obj("request")
        assertEquals("mcp_toggle", request.string("subtype"))
        assertEquals("jetbrains", request.string("serverName"))
        assertEquals(false, request.bool("enabled"))
    }

    @Test
    fun `mcpSetServersRequest carries servers object`() {
        val servers = buildJsonObject { put("jetbrains", buildJsonObject { put("type", "stdio") }) }
        val request = parse(ControlProtocol.mcpSetServersRequest("r25", servers)).obj("request")
        assertEquals("mcp_set_servers", request.string("subtype"))
        assertEquals(servers, request["servers"])
    }

    @Test
    fun `mcpCallRequest carries tool and arguments`() {
        val args = buildJsonObject { put("path", "a.kt") }
        val request = parse(ControlProtocol.mcpCallRequest("r26", "mcp__jetbrains__read_file", args)).obj("request")
        assertEquals("mcp_call", request.string("subtype"))
        assertEquals("mcp__jetbrains__read_file", request.string("tool"))
        assertEquals(args, request["arguments"])
    }

    @Test
    fun `mcpCallRequest omits arguments by default`() {
        val request = parse(ControlProtocol.mcpCallRequest("r27", "mcp__jetbrains__ping")).obj("request")
        assertTrue(!request.containsKey("arguments"))
    }

    @Test
    fun `applyFlagSettingsRequest carries settings object`() {
        val settings = buildJsonObject { put("verbose", true) }
        val request = parse(ControlProtocol.applyFlagSettingsRequest("r28", settings)).obj("request")
        assertEquals("apply_flag_settings", request.string("subtype"))
        assertEquals(settings, request["settings"])
    }

    @Test
    fun `userDialogCancelled replies with behavior cancelled`() {
        val root = parse(ControlProtocol.userDialogCancelled("r30"))
        val response = root.obj("response")
        assertEquals("success", response.string("subtype"))
        assertEquals("r30", response.string("request_id"))
        assertEquals("cancelled", response.obj("response").string("behavior"))
    }

    @Test
    fun `userDialogCompleted carries behavior completed and result`() {
        val result = buildJsonObject { put("choice", "yes") }
        val inner = parse(ControlProtocol.userDialogCompleted("r31", result)).obj("response").obj("response")
        assertEquals("completed", inner.string("behavior"))
        assertEquals(result, inner["result"])
    }

    @Test
    fun `elicitationResult omits content when null`() {
        val inner = parse(ControlProtocol.elicitationResult("r32", "decline")).obj("response").obj("response")
        assertEquals("decline", inner.string("action"))
        assertTrue(!inner.containsKey("content"))
    }

    @Test
    fun `elicitationResult carries content for accept`() {
        val content = buildJsonObject { put("name", "ada") }
        val inner = parse(ControlProtocol.elicitationResult("r33", "accept", content)).obj("response").obj("response")
        assertEquals("accept", inner.string("action"))
        assertEquals(content, inner["content"])
    }

    @Test
    fun `newRequestId is prefixed and unique`() {
        val a = ControlProtocol.newRequestId()
        val b = ControlProtocol.newRequestId()
        assertTrue(a.startsWith("req_"))
        assertTrue(a != b)
    }
}
