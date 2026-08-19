package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [AgentEnding] against the record shapes the binary actually writes into `agent-<id>.jsonl`.
 *
 * **Why four verdicts and not two.** A settled status is per-process memory, so an agent restored from a
 * previous run carries nothing and its own transcript is the only evidence there is. With only "finished" and
 * "cut off", two different agents get the same wrong answer: a transcript that grew past a turn it had already
 * closed is a RESUMED agent, painted red as if it had failed — and a transcript the binary or the user STOPPED
 * is painted as if it were still working, for ever, because nothing more will ever be appended to it. Each
 * verdict here maps to one liveness, which is what keeps those cases apart.
 *
 * Pure: lines in, verdict out. No IDE, no filesystem, no clock.
 */
class AgentEndingTest {

    /**
     * The lines a real transcript holds, through the REAL parser — the one [dev.lain.claudejb.session.AgentRegistry.scan]
     * feeds it from, so a change to what counts as a record cannot pass here and fail in production.
     *
     * These fixtures stay written as JSONL text rather than as built objects on purpose: what is being judged
     * is the shape the binary writes, and a hand-built `JsonObject` would let a test pass over a shape the
     * file never contains. It also keeps the blank-line and malformed-line cases below meaningful — they now
     * assert that [SessionTranscriptReader.parseRecords] drops them before the verdict is ever asked for.
     */
    private fun records(lines: List<String>) = SessionTranscriptReader.parseRecords(lines)

    /** A finished assistant turn — the one record shape that closes a turn. */
    private val endTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[]}}"""

    /** An assistant turn parked on a tool that never came back. */
    private val toolUse = """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[]}}"""

    /** A tool result handed to the agent — a record, and not an ending: nobody answered it. */
    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""

    @Test
    fun `a transcript whose last record closes its turn is completed`() {
        val lines = listOf(toolUse, toolResult, endTurn)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a transcript with one record after a closed turn was resumed`() {
        // THE BUG this verdict exists for: the agent closed a turn and then wrote again, so it is alive. Read as
        // "cut off" it comes back red, asserting a failure that never happened.
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
        // The result arrived and nothing answered it: the turn is open, whatever the agent was doing.
        val lines = listOf(toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `no lines at all is nothing to judge`() {
        assertNull(AgentEnding.of(records(emptyList())))
    }

    @Test
    fun `only blank lines is nothing to judge`() {
        // A blank line is not a record, so a file of them says no more than an empty one. The caller must be
        // able to tell "the binary has written nothing yet" from "it wrote and never closed the turn".
        val lines = listOf("", "   ", "\t")

        assertNull(AgentEnding.of(records(lines)))
    }

    @Test
    fun `blank lines after a closed turn do not make it a resumption`() {
        // A trailing newline is how a line-oriented file ends; reading that as a further record would reopen
        // every completed agent on disk.
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
        // Safe casts rather than `jsonPrimitive`: a newer binary writing a different shape here must cost a
        // verdict, never an exception in the middle of a scan.
        val lines = listOf("""{"type":"assistant","message":{"stop_reason":{"kind":"end_turn"}}}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `a record whose message has the wrong shape is not an ending`() {
        val lines = listOf("""{"type":"assistant","message":"end_turn"}""")

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    // ── the second shape a finished turn comes in ────────────────────────────────────────────────────────
    //
    // Measured over the agent transcripts on one developer machine: 41 end on a real model's final answer
    // carrying NO `stop_reason` at all, and `end_turn` alone called every one of them cut off — i.e. painted a
    // finished agent red, asserting a failure that never happened.

    /** The agent's answer, with no `stop_reason` recorded on it — 41 transcripts end exactly here. */
    private val bareAnswer =
        """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"done"}]}}"""

    @Test
    fun `a final answer with no stop_reason is completed`() {
        val lines = listOf(toolUse, toolResult, bareAnswer)

        assertEquals(AgentEnding.Ending.COMPLETED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `the same record mid-transcript is not an ending`() {
        // The exclusion that keeps this a SECOND rule rather than a looser one. Admitting a text-only
        // assistant record into the resumption scan turned 100 of those transcripts into "resumed", i.e.
        // a hundred dead agents painted as live — the exact mistake in the other direction.
        val lines = listOf(bareAnswer, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `an answer still holding a tool call is waiting, not finished`() {
        val pending =
            """{"type":"assistant","message":{"role":"assistant","model":"claude-opus-5","content":[{"type":"text","text":"one moment"},{"type":"tool_use","id":"t1","name":"Read"}]}}"""

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(listOf(pending))))
    }

    // ── work that STOPPED: the two markers, and why they are not "unfinished" ────────────────────────────
    //
    // THE "AGENTS STUCK ON GREEN" BUG. A cancelled agent and one the binary cut off both leave a transcript
    // with no closed turn at the end, so both used to read as work still in flight — and unlike a genuinely
    // open turn, NOTHING will ever be appended that could correct it. Measured over the 672 agent transcripts
    // on one developer machine, 155 end on one of these two records: 77 cancellations, 78 cut-offs.

    /** What the BINARY writes when it cuts an agent off: its own record, under a reserved model name. */
    private val sessionLimit =
        """{"type":"assistant","message":{"role":"assistant","model":"<synthetic>","stop_reason":"stop_sequence","content":[{"type":"text","text":"You've hit your session limit · resets 3:30am (Europe/Madrid)"}]}}"""

    /** What the binary writes into the agent's own file when the user cancels it. */
    private val interrupted =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""

    @Test
    fun `a synthetic ending is the binary cutting the agent off, not the agent finishing`() {
        // Not COMPLETED — the work stopped mid-flight and there is no answer. Not UNFINISHED either, which is
        // what it used to answer: that reads as "still going" and no further record is coming.
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
        // Both spellings occur in that corpus (67 and 10 files), so the match is on the shared prefix.
        val variant =
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}"""

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(listOf(toolUse, variant))))
    }

    @Test
    fun `a cancellation whose content is a plain string is the same ending`() {
        // The binary writes `content` both ways; a record read only through the block array misses this one.
        val asString = """{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}"""

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(listOf(toolUse, asString))))
    }

    @Test
    fun `an ending outranks a turn the agent had closed before it`() {
        // THE WORST CASE, and the one seen in the field: closed a turn, was resumed, and was then cancelled.
        // RESUMED answers RUNNING unconditionally — no parent, no restore flag, nothing can soften it — so
        // this agent stayed green for the rest of the session with its own file saying otherwise.
        val lines = listOf(endTurn, toolUse, toolResult, interrupted)

        assertEquals(AgentEnding.Ending.ABORTED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `an interruption the agent worked past is not an ending`() {
        // The exclusion that keeps this from killing live agents: only the LAST record can abort. An agent
        // interrupted and then resumed carries the marker mid-file and is demonstrably still working.
        val lines = listOf(interrupted, toolUse, toolResult)

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(lines)))
    }

    @Test
    fun `text that merely mentions an interruption is not one`() {
        // The marker is the WHOLE leading text of a record the binary wrote, not a substring of the agent's
        // prose — an agent reporting on this very bug would otherwise mark itself dead.
        val prose =
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"the log said [Request interrupted by user] and I moved on"}]}}"""

        assertEquals(AgentEnding.Ending.UNFINISHED, AgentEnding.of(records(listOf(toolUse, prose))))
    }
}
