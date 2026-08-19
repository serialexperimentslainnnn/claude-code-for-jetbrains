package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtocolParserToolOutputTest {

    private fun line(body: String) = ProtocolParser.parse(body).filterIsInstance<ClaudeEvent.ToolResult>()

    @Test
    fun `a backgrounded command carries its task id`() {
        val events = line(
            """
            {"type":"user","parent_tool_use_id":"toolu_agent",
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_bash","content":"Running in the background"}]},
             "tool_use_result":{"stdout":"","stderr":"","interrupted":false,"backgroundTaskId":"beaz86nuu"}}
            """.trimIndent(),
        )
        val result = events.single()
        assertEquals("beaz86nuu", result.output?.backgroundTaskId)
        assertEquals("toolu_agent", result.parentToolUseId)
    }

    @Test
    fun `a backgrounded agent carries its progress file`() {
        val result = line(
            """
            {"type":"user","parent_tool_use_id":null,
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_task","content":"backgrounded"}]},
             "tool_use_result":{"status":"backgrounded","outputFile":"/tmp/agent-1.out","description":"x"}}
            """.trimIndent(),
        ).single()
        assertEquals("/tmp/agent-1.out", result.output?.outputFile)
        assertNull(result.output?.backgroundTaskId)
    }

    @Test
    fun `a result with nothing we act on reports no structured output`() {
        val result = line(
            """
            {"type":"user","parent_tool_use_id":null,
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_read","content":"file contents"}]},
             "tool_use_result":{"type":"text","file":{"filePath":"/tmp/a"}}}
            """.trimIndent(),
        ).single()
        assertNull(result.output)
    }

    @Test
    fun `two results in one message get no structured output at all`() {
        val results = line(
            """
            {"type":"user","parent_tool_use_id":null,
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_a","content":"a"},
               {"type":"tool_result","tool_use_id":"toolu_b","content":"b"}]},
             "tool_use_result":{"backgroundTaskId":"bxxx"}}
            """.trimIndent(),
        )
        assertEquals(2, results.size)
        results.forEach { assertNull(it.output) }
    }

    @Test
    fun `a missing or malformed tool_use_result never costs the result`() {
        val plain = line(
            """
            {"type":"user","parent_tool_use_id":null,
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_x","content":"ok"}]}}
            """.trimIndent(),
        ).single()
        assertNull(plain.output)
        assertEquals("ok", plain.content)

        val weird = line(
            """
            {"type":"user","parent_tool_use_id":null,
             "message":{"role":"user","content":[
               {"type":"tool_result","tool_use_id":"toolu_x","content":"ok"}]},
             "tool_use_result":"not an object"}
            """.trimIndent(),
        ).single()
        assertNull(weird.output)
    }
}
