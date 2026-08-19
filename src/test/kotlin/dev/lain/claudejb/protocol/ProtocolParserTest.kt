package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolParserTest {

    private inline fun <reified T : ClaudeEvent> parseOne(line: String): T {
        val events = ProtocolParser.parse(line)
        assertEquals(1, events.size, "expected exactly one event from: $line")
        return assertInstanceOf(T::class.java, events.first())
    }

    @Test
    fun `blank line yields nothing`() {
        assertTrue(ProtocolParser.parse("   ").isEmpty())
        assertTrue(ProtocolParser.parse("").isEmpty())
    }

    @Test
    fun `malformed json degrades to Other instead of throwing`() {
        val event = parseOne<ClaudeEvent.Other>("{not valid json")
        assertEquals("?", event.type)
    }

    @Test
    fun `missing type field degrades to Other`() {
        parseOne<ClaudeEvent.Other>("""{"foo":"bar"}""")
    }

    @Test
    fun `keep_alive is ignored`() {
        assertTrue(ProtocolParser.parse("""{"type":"keep_alive"}""").isEmpty())
    }

    @Test
    fun `unknown keys do not break decoding (lenient)`() {
        val line = """{"type":"system","subtype":"init","session_id":"s1","brand_new_field":42,"slash_commands":["a"]}"""
        val event = parseOne<ClaudeEvent.Init>(line)
        assertEquals("s1", event.info.sessionId)
        assertEquals(listOf("a"), event.info.slashCommands)
    }

    @Test
    fun `system init carries session id and commands`() {
        val line = """{"type":"system","subtype":"init","session_id":"abc","permissionMode":"plan","slash_commands":["clear","cost"]}"""
        val event = parseOne<ClaudeEvent.Init>(line)
        assertEquals("abc", event.info.sessionId)
        assertEquals("plan", event.info.permissionMode)
        assertEquals(listOf("clear", "cost"), event.info.slashCommands)
    }

    @Test
    fun `system status compacting becomes a notice`() {
        val event = parseOne<ClaudeEvent.StatusNotice>("""{"type":"system","subtype":"status","status":"compacting"}""")
        assertTrue(event.text.contains("Compacting", ignoreCase = true))
    }

    @Test
    fun `system status compact result success becomes a notice`() {
        val event = parseOne<ClaudeEvent.StatusNotice>("""{"type":"system","subtype":"status","compact_result":"success"}""")
        assertTrue(event.text.contains("compacted", ignoreCase = true))
    }

    @Test
    fun `local command output is captured`() {
        val event = parseOne<ClaudeEvent.LocalCommandOutput>(
            """{"type":"system","subtype":"local_command_output","content":"hello"}""",
        )
        assertEquals("hello", event.content)
    }

    @Test
    fun `assistant message fans out text thinking and tool_use in order`() {
        val line = """
            {"type":"assistant","message":{"role":"assistant","content":[
              {"type":"thinking","thinking":"hmm"},
              {"type":"text","text":"hi"},
              {"type":"tool_use","id":"t1","name":"Read","input":{"file":"a.kt"}}
            ]}}
        """.trimIndent()
        val events = ProtocolParser.parse(line)
        assertEquals(3, events.size)
        assertInstanceOf(ClaudeEvent.AssistantThinking::class.java, events[0])
        val text = assertInstanceOf(ClaudeEvent.AssistantText::class.java, events[1])
        assertEquals("hi", text.text)
        val tool = assertInstanceOf(ClaudeEvent.ToolUse::class.java, events[2])
        assertEquals("t1", tool.id)
        assertEquals("Read", tool.name)
    }

    @Test
    fun `empty assistant text block is dropped`() {
        val line = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":""}]}}"""
        assertTrue(ProtocolParser.parse(line).isEmpty())
    }

    @Test
    fun `subagent tool_use keeps parent_tool_use_id for nesting`() {
        val line = """{"type":"assistant","parent_tool_use_id":"agent1","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"Grep","input":{}}]}}"""
        val tool = parseOne<ClaudeEvent.ToolUse>(line)
        assertEquals("agent1", tool.parentToolUseId)
    }

    @Test
    fun `subagent thinking keeps parent_tool_use_id for nesting`() {
        val line = """{"type":"assistant","parent_tool_use_id":"agent1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"}]}}"""
        val thinking = parseOne<ClaudeEvent.AssistantThinking>(line)
        assertEquals("agent1", thinking.parentToolUseId)
    }

    @Test
    fun `main-transcript thinking carries no parent_tool_use_id`() {
        val line = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"}]}}"""
        assertNull(parseOne<ClaudeEvent.AssistantThinking>(line).parentToolUseId)
    }

    @Test
    fun `user tool_result with string content`() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"done","is_error":false}]}}"""
        val event = parseOne<ClaudeEvent.ToolResult>(line)
        assertEquals("t1", event.toolUseId)
        assertEquals("done", event.content)
        assertFalse(event.isError)
    }

    @Test
    fun `user tool_result with array content joins text blocks`() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}],"is_error":true}]}}"""
        val event = parseOne<ClaudeEvent.ToolResult>(line)
        assertEquals("a\nb", event.content)
        assertTrue(event.isError)
    }

    @Test
    fun `tool_use_error wrapper is stripped from the rendered text`() {
        val inner = "Error: No such tool available: Glob. Glob is not available in this session."
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result",""" +
            """"tool_use_id":"t9","content":"<tool_use_error>$inner</tool_use_error>","is_error":true}]}}"""
        val event = parseOne<ClaudeEvent.ToolResult>(line)
        assertEquals(inner, event.content)
        assertTrue(event.isError)
    }

    @Test
    fun `a tool result that merely mentions the tag is left intact`() {
        val body = "see <tool_use_error> in the CLI bundle"
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result",""" +
            """"tool_use_id":"t10","content":"$body","is_error":false}]}}"""
        assertEquals(body, parseOne<ClaudeEvent.ToolResult>(line).content)
    }

    @Test
    fun `stream message_start is a boundary`() {
        parseOne<ClaudeEvent.MessageStart>("""{"type":"stream_event","event":{"type":"message_start"}}""")
    }

    @Test
    fun `stream content_block_delta text_delta becomes TextDelta`() {
        val event = parseOne<ClaudeEvent.TextDelta>(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"par"}}}""",
        )
        assertEquals("par", event.text)
    }

    @Test
    fun `stream message_delta usage becomes LiveUsage with all four token components`() {
        val event = parseOne<ClaudeEvent.LiveUsage>(
            """{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":6,"cache_creation_input_tokens":29195,"cache_read_input_tokens":0,"output_tokens":123}}}""",
        )
        assertEquals(6, event.inputTokens)
        assertEquals(29195, event.cacheCreationTokens)
        assertEquals(0, event.cacheReadTokens)
        assertEquals(123, event.outputTokens)
    }

    @Test
    fun `stream message_start also surfaces usage as a LiveUsage (so the live counter is right from t=0)`() {
        val events = ProtocolParser.parse(
            """{"type":"stream_event","event":{"type":"message_start","message":{"usage":{"input_tokens":6,"cache_creation_input_tokens":29195,"output_tokens":1}}}}""",
        )
        assertTrue(events.any { it is ClaudeEvent.MessageStart })
        val live = events.filterIsInstance<ClaudeEvent.LiveUsage>().single()
        assertEquals(6, live.inputTokens)
        assertEquals(29195, live.cacheCreationTokens)
        assertEquals(1, live.outputTokens)
    }

    @Test
    fun `stream message_start without usage yields only the boundary`() {
        val events = ProtocolParser.parse(
            """{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1"}}}""",
        )
        assertEquals(1, events.size)
        assertInstanceOf(ClaudeEvent.MessageStart::class.java, events.first())
    }

    @Test
    fun `stream content_block_delta thinking_delta becomes ThinkingDelta`() {
        val event = parseOne<ClaudeEvent.ThinkingDelta>(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"reasoning"}}}""",
        )
        assertEquals("reasoning", event.text)
    }

    @Test
    fun `stream text_delta takes parent_tool_use_id from the root, not from event`() {
        val event = parseOne<ClaudeEvent.TextDelta>(
            """{"type":"stream_event","parent_tool_use_id":"agent7","event":{"type":"content_block_delta","parent_tool_use_id":"decoy","delta":{"type":"text_delta","text":"par"}}}""",
        )
        assertEquals("agent7", event.parentToolUseId)
    }

    @Test
    fun `stream thinking_delta takes parent_tool_use_id from the root`() {
        val event = parseOne<ClaudeEvent.ThinkingDelta>(
            """{"type":"stream_event","parent_tool_use_id":"agent7","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"reasoning"}}}""",
        )
        assertEquals("agent7", event.parentToolUseId)
    }

    @Test
    fun `main-transcript deltas carry no parent_tool_use_id`() {
        val absent = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"par"}}}"""
        val explicitNull =
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"r"}}}"""
        assertNull(parseOne<ClaudeEvent.TextDelta>(absent).parentToolUseId)
        assertNull(parseOne<ClaudeEvent.ThinkingDelta>(explicitNull).parentToolUseId)
    }

    @Test
    fun `stream message_delta with partial usage zero-fills the missing token components`() {
        val event = parseOne<ClaudeEvent.LiveUsage>(
            """{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":42}}}""",
        )
        assertEquals(0, event.inputTokens)
        assertEquals(0, event.cacheCreationTokens)
        assertEquals(0, event.cacheReadTokens)
        assertEquals(42, event.outputTokens)
    }

    @Test
    fun `stream message_delta without usage yields nothing`() {
        assertTrue(
            ProtocolParser.parse("""{"type":"stream_event","event":{"type":"message_delta"}}""").isEmpty(),
        )
    }

    @Test
    fun `stream content_block_delta with unknown delta type yields nothing`() {
        assertTrue(
            ProtocolParser.parse(
                """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{"}}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `stream content_block_delta text_delta without text yields nothing`() {
        assertTrue(
            ProtocolParser.parse(
                """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta"}}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `stream_event without an event object yields nothing`() {
        assertTrue(ProtocolParser.parse("""{"type":"stream_event"}""").isEmpty())
    }

    @Test
    fun `stream_event with an unknown event type yields nothing`() {
        assertTrue(
            ProtocolParser.parse(
                """{"type":"stream_event","event":{"type":"content_block_start","index":0}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `result end of turn carries cost and session`() {
        val line = """{"type":"result","subtype":"success","result":"ok","total_cost_usd":0.12,"session_id":"s9"}"""
        val event = parseOne<ClaudeEvent.Result>(line)
        assertEquals("success", event.result.subtype)
        assertEquals(0.12, event.result.totalCostUsd)
        assertEquals("s9", event.result.sessionId)
    }

    @Test
    fun `can_use_tool control_request becomes PermissionRequest`() {
        val line = """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"x"},"tool_use_id":"tu1"}}"""
        val event = parseOne<ClaudeEvent.PermissionRequest>(line)
        assertEquals("r1", event.requestId)
        assertEquals("Write", event.request.toolName)
        assertEquals("tu1", event.request.toolUseId)
    }

    @Test
    fun `hook_callback control_request becomes HookCallback carrying the request`() {
        val line = """{"type":"control_request","request_id":"r9","request":{"subtype":"hook_callback","callback_id":"cb1","input":{"hook_event_name":"PreToolUse","tool_name":"Bash"}}}"""
        val event = parseOne<ClaudeEvent.HookCallback>(line)
        assertEquals("r9", event.requestId)
        assertEquals("hook_callback", (event.request["subtype"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `unknown control_request subtype must still be answered`() {
        val line = """{"type":"control_request","request_id":"r2","request":{"subtype":"mcp_message"}}"""
        val event = parseOne<ClaudeEvent.UnsupportedControlRequest>(line)
        assertEquals("r2", event.requestId)
        assertEquals("mcp_message", event.subtype)
    }

    @Test
    fun `control_response success is correlated by request id`() {
        val line = """{"type":"control_response","response":{"subtype":"success","request_id":"r3","response":{"ok":true}}}"""
        val event = parseOne<ClaudeEvent.ControlResult>(line)
        assertEquals("r3", event.requestId)
        assertTrue(event.success)
    }

    @Test
    fun `control_response error surfaces the message`() {
        val line = """{"type":"control_response","response":{"subtype":"error","request_id":"r4","error":"boom"}}"""
        val event = parseOne<ClaudeEvent.ControlResult>(line)
        assertFalse(event.success)
        assertEquals("boom", event.error)
    }

    @Test
    fun `rate_limit_event becomes RateLimit`() {
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","rateLimitType":"five_hour","utilization":0.92}}"""
        val event = parseOne<ClaudeEvent.RateLimit>(line)
        assertEquals("allowed_warning", event.info.status)
        assertEquals("Current session", RateLimitInfo.windowTitleFor(event.info.rateLimitType))
        assertEquals(92, event.info.utilizationPercent())
    }
}
