package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [AgentEnding] against the record shapes the binary actually writes into `agent-<id>.jsonl`.
 *
 * **Why three verdicts and not two.** A settled status is per-process memory, so an agent restored from a
 * previous run carries nothing and its own transcript is the only evidence there is. With only "finished" and
 * "cut off", a transcript that grew past a turn it had already closed had to be answered with one of them — and
 * both answers are wrong about a different agent: a resumed agent painted red as if it had failed, or a
 * genuinely cut-off agent painted as if it were still working. Each verdict here maps to one liveness, which is
 * what keeps those two apart.
 *
 * Pure: lines in, verdict out. No IDE, no filesystem, no clock.
 */
class AgentEndingTest {

    /** A finished assistant turn — the one record shape that closes a turn. */
    private val endTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[]}}"""

    /** An assistant turn parked on a tool that never came back. */
    private val toolUse = """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[]}}"""

    /** A tool result handed to the agent — a record, and not an ending: nobody answered it. */
    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""

    @Test
    fun `a transcript whose last record closes its turn is completed`() {
        val lines = listOf(toolUse, toolResult, endTurn)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(lines))
    }

    @Test
    fun `a transcript with one record after a closed turn was resumed`() {
        // THE BUG this verdict exists for: the agent closed a turn and then wrote again, so it is alive. Read as
        // "cut off" it comes back red, asserting a failure that never happened.
        val lines = listOf(endTurn, toolResult)

        assertEquals(AgentEnding.Ending.RESUMED, AgentEnding.of(lines))
    }

    @Test
    fun `a transcript with several records after a closed turn was resumed`() {
        val lines = listOf(endTurn, toolResult, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.RESUMED, AgentEnding.of(lines))
    }

    @Test
    fun `a transcript still waiting on a tool never finished`() {
        val lines = listOf(toolResult, toolUse)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    @Test
    fun `a transcript ending on a delivered result never finished`() {
        // The result arrived and nothing answered it: the turn is open, whatever the agent was doing.
        val lines = listOf(toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    @Test
    fun `no lines at all is nothing to judge`() {
        assertNull(AgentEnding.of(emptyList()))
    }

    @Test
    fun `only blank lines is nothing to judge`() {
        // A blank line is not a record, so a file of them says no more than an empty one. The caller must be
        // able to tell "the binary has written nothing yet" from "it wrote and never closed the turn".
        val lines = listOf("", "   ", "\t")

        assertNull(AgentEnding.of(lines))
    }

    @Test
    fun `blank lines after a closed turn do not make it a resumption`() {
        // A trailing newline is how a line-oriented file ends; reading that as a further record would reopen
        // every completed agent on disk.
        val lines = listOf(toolUse, endTurn, "", "  ")

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(lines))
    }

    @Test
    fun `unparseable lines before a closed turn are skipped, not fatal`() {
        val lines = listOf("not json at all", """{"type":"assistant",""", endTurn)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(lines))
    }

    @Test
    fun `a record whose stop_reason has the wrong shape is not an ending`() {
        // Safe casts rather than `jsonPrimitive`: a newer binary writing a different shape here must cost a
        // verdict, never an exception in the middle of a scan.
        val lines = listOf("""{"type":"assistant","message":{"stop_reason":{"kind":"end_turn"}}}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    @Test
    fun `a record whose message has the wrong shape is not an ending`() {
        val lines = listOf("""{"type":"assistant","message":"end_turn"}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    // ── the second shape a finished turn comes in ────────────────────────────────────────────────────────
    //
    // Measured over the 566 agent transcripts on one developer machine: 41 end on a real model's final answer
    // carrying NO `stop_reason` at all, and `end_turn` alone called every one of them cut off — i.e. painted a
    // finished agent red, asserting a failure that never happened.

    /** The agent's answer, with no `stop_reason` recorded on it — 41 of 566 transcripts end exactly here. */
    private val bareAnswer =
        """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"done"}]}}"""

    /** What the BINARY writes when it cuts an agent off: its own record, under a reserved model name. */
    private val sessionLimit =
        """{"type":"assistant","message":{"role":"assistant","model":"<synthetic>","stop_sequence":"","content":[{"type":"text","text":"You've hit your session limit"}]}}"""

    @Test
    fun `a final answer with no stop_reason is completed`() {
        val lines = listOf(toolUse, toolResult, bareAnswer)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(lines))
    }

    @Test
    fun `the same record mid-transcript is not an ending`() {
        // The exclusion that keeps this a SECOND rule rather than a looser one. Admitting a text-only
        // assistant record into the resumption scan turned 100 of those 566 transcripts into "resumed", i.e.
        // a hundred dead agents painted as live — the exact mistake in the other direction.
        val lines = listOf(bareAnswer, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    @Test
    fun `a synthetic ending is the binary cutting the agent off, not the agent finishing`() {
        // Every synthetic ending in that corpus is "You've hit your session limit": work stopped mid-flight,
        // which must keep reading as cut off however text-shaped the record is.
        val lines = listOf(toolUse, toolResult, sessionLimit)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(lines))
    }

    @Test
    fun `an answer still holding a tool call is waiting, not finished`() {
        val pending =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"one moment"},{"type":"tool_use","id":"t1","name":"Read"}]}}"""

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(listOf(pending)))
    }
}
