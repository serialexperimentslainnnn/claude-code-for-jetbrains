package dev.lain.claudejb.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `tool_use_result` — the tool's STRUCTURED output — as it reaches the stream.
 *
 * The SDK is explicit that this is the thing to read from: *"the tool's full Output object, not the string
 * content sent to the model […] render from it instead of parsing the tool_result text"*
 * (`SDKUserMessageReplay.tool_use_result`, SDK 0.3.223). It is also the ONLY place a background task's id
 * appears next to the tool call that started it — `background_tasks_changed` carries ids and nothing else —
 * so without this parse a background task has no owner, no card and no output.
 *
 * The fixture mirrors a real line from a session transcript (`claude` 2.1.226), where the same object is
 * spelled `toolUseResult`; on the stream it is `tool_use_result`.
 */
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
        // The owner comes from the line, not from the structured object: it is the agent whose turn ran it.
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
        // Not an empty object: "nothing here for us" and "a task with blank fields" are different claims.
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
        // The object sits at the line's root and describes ONE call, with no field saying which. Attaching it
        // to both would invent a background-task link the protocol never stated.
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
