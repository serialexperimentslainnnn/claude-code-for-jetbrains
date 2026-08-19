package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentEndingTest {

    private fun records(lines: List<String>) = SessionTranscriptReader.parseRecords(lines)

    private val endTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[]}}"""

    private val toolUse = """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[]}}"""

    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""

    @Test
    fun `a transcript whose last record closes its turn is completed`() {
        val lines = listOf(toolUse, toolResult, endTurn)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a transcript with one record after a closed turn was resumed`() {
        val lines = listOf(endTurn, toolResult)

        assertEquals(AgentEnding.Ending.RESUMED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a transcript with several records after a closed turn was resumed`() {
        val lines = listOf(endTurn, toolResult, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.RESUMED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a transcript still waiting on a tool never finished`() {
        val lines = listOf(toolResult, toolUse)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a transcript ending on a delivered result never finished`() {
        val lines = listOf(toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `no lines at all is nothing to judge`() {
        assertNull(AgentEnding.of(records(emptyList())))
    }

    @Test
    fun `only blank lines is nothing to judge`() {
        val lines = listOf("", "   ", "\t")

        assertNull(AgentEnding.of(records(lines)))
    }

    @Test
    fun `blank lines after a closed turn do not make it a resumption`() {
        val lines = listOf(toolUse, endTurn, "", "  ")

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `unparseable lines before a closed turn are skipped, not fatal`() {
        val lines = listOf("not json at all", """{"type":"assistant",""", endTurn)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a record whose stop_reason has the wrong shape is not an ending`() {
        val lines = listOf("""{"type":"assistant","message":{"stop_reason":{"kind":"end_turn"}}}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a record whose message has the wrong shape is not an ending`() {
        val lines = listOf("""{"type":"assistant","message":"end_turn"}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    private val bareAnswer =
        """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"done"}]}}"""

    @Test
    fun `a final answer with no stop_reason is completed`() {
        val lines = listOf(toolUse, toolResult, bareAnswer)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `the same record mid-transcript is not an ending`() {
        val lines = listOf(bareAnswer, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `an answer still holding a tool call is waiting, not finished`() {
        val pending =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"one moment"},{"type":"tool_use","id":"t1","name":"Read"}]}}"""

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(listOf(pending))))
    }

    private val sessionLimit =
        """{"type":"assistant","message":{"role":"assistant","model":"<synthetic>","stop_reason":"stop_sequence","content":[{"type":"text","text":"You've hit your session limit · resets 3:30am (Europe/Madrid)"}]}}"""

    private val interrupted =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""

    @Test
    fun `a synthetic ending is the binary cutting the agent off, not the agent finishing`() {
        val lines = listOf(toolUse, toolResult, sessionLimit)

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a cancelled agent is stopped, not still working`() {
        val lines = listOf(toolUse, toolResult, interrupted)

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `the for-tool-use variant of the cancellation is the same ending`() {
        val variant =
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}"""

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(listOf(toolUse, variant))))
    }

    @Test
    fun `a cancellation whose content is a plain string is the same ending`() {
        val asString = """{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}"""

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(listOf(toolUse, asString))))
    }

    @Test
    fun `an ending outranks a turn the agent had closed before it`() {
        val lines = listOf(endTurn, toolUse, toolResult, interrupted)

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `an interruption the agent worked past is not an ending`() {
        val lines = listOf(interrupted, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `text that merely mentions an interruption is not one`() {
        val prose =
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"the log said [Request interrupted by user] and I moved on"}]}}"""

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(listOf(toolUse, prose))))
    }
}
