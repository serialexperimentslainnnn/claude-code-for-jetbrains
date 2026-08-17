package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [AgentRegistry] against a real `subagents/` directory laid out the way the binary lays it out.
 *
 * The rule under test is the one that matters: **which agents are ours**. The same session id can be resumed
 * from the terminal, so the directory mixes agents this plugin spawned with agents it never saw — one real
 * session had 84 — and getting this wrong means either dozens of phantom tabs or an invisible agent tree.
 */
class AgentRegistryTest {

    @TempDir
    lateinit var dir: Path

    /**
     * Writes one agent the way the binary writes it: the FILE carries the `agent-` prefix, the id inside
     * `parentAgentId` does not. Reading those two as the same string is what collapsed the tree in 5.5.0 —
     * no parent ever matched a node — so the fixtures mirror the real shape rather than a tidy one.
     */
    private fun agent(id: String, toolUseId: String? = null, parent: String? = null, depth: Int = 1, text: String? = null) {
        val meta = buildString {
            append("""{"agentType":"general-purpose","description":"Task $id"""")
            toolUseId?.let { append(""","toolUseId":"$it"""") }
            parent?.let { append(""","parentAgentId":"$it"""") }
            append(""","spawnDepth":$depth}""")
        }
        Files.writeString(dir.resolve("${AgentMeta.FILE_PREFIX}$id${AgentMeta.META_SUFFIX}"), meta)
        if (text != null) {
            val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
            Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), line)
        }
    }

    /**
     * The registry's clock, moved by hand. It is injected rather than read inside the registry so a stop
     * instant can be asserted as a literal — the alternative is recomputing it here with the subject's own
     * expression, which asserts nothing.
     */
    private var clock = 1_000_000_000L

    /** When this run of the plugin started, as the registry is told it: the stamp an already-finished agent gets. */
    private val runStarted = 900_000_000L

    private fun registry() = AgentRegistry(subagentsDir = { dir }, now = { clock }, runStartedAtMillis = runStarted)

    /** Writes an agent whose transcript ENDS the way a real one does — see [AgentEnding]. */
    private fun agentEnding(id: String, stopReason: String?) {
        agent(id, depth = 1)
        val line = if (stopReason == null) {
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""
        } else {
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"$stopReason","content":[]}}"""
        }
        Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), line)
    }

    /** A finished assistant turn: the one record shape that closes a turn — see [AgentEnding]. */
    private val closedTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[]}}"""

    /** An assistant turn parked on a tool that never came back. */
    private val openTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[]}}"""

    /** A tool result handed to the agent — a record, and not an ending: nobody answered it. */
    private val deliveredResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""

    /**
     * Rewrites an agent's transcript file with exactly these records, in this order.
     *
     * Growth is the whole subject of the tests below, and the binary grows that file by appending records — so
     * a test states the file's full contents before and after, and the difference between the two IS the
     * evidence the registry reads.
     */
    private fun writeTranscript(id: String, vararg lines: String) {
        Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), lines.joinToString("\n"))
    }

    @Test
    fun `an agent whose Task call we never saw is not shown`() {
        // THE POINT: these files exist on disk and belong to a terminal run. Showing "whatever is in the
        // directory" is what would reopen a heavy session with dozens of tabs the plugin never spawned.
        agent("foreign", toolUseId = "toolu_terminal")
        val reg = registry()
        assertTrue(reg.scan().isEmpty())
        assertTrue(reg.nodes.isEmpty())
    }

    @Test
    fun `an agent whose Task call we saw is admitted, with its label and transcript`() {
        agent("mine", toolUseId = "toolu_ours", text = "hello from the agent")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        assertEquals(listOf("mine"), reg.scan())
        val node = reg.nodes.getValue("mine")
        assertEquals("Task mine", node.meta.label())
        // COMPLETED, not RUNNING: the fixture's one record is a text-only assistant answer with nothing after
        // it, which is a finished turn — and in a LIVE session that transcript is now read, where it used to
        // be ignored in favour of a flat "we watched it start, so it is running".
        assertEquals(AgentStatus.COMPLETED, node.status)
        // Parsed by the same reader the session restore uses — one code path for live and restored.
        assertEquals(1, node.entries.size)
        assertEquals("hello from the agent", node.entries.first().text)
    }

    @Test
    fun `admission is inherited down the chain, however deep`() {
        // A nested agent is spawned INSIDE another agent's turn, so its task_started never reaches the main
        // stream: there is no tool_use_id of its own for us to have observed. Without inheritance every
        // level below the first would be invisible, which is exactly the tree the user asked to see.
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", parent = "a1", depth = 2)
        agent("a3", parent = "a2", depth = 3)
        agent("a4", parent = "a3", depth = 4)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(setOf("a1", "a2", "a3", "a4"), reg.nodes.keys)
        assertEquals(listOf("a2"), reg.children("a1").map { it.agentId })
        assertEquals(listOf("a1"), reg.children(null).map { it.agentId })
    }

    @Test
    fun `a foreign subtree stays out even when ours is present`() {
        agent("mine", toolUseId = "toolu_ours")
        agent("foreign", toolUseId = "toolu_terminal")
        agent("foreign-child", parent = "foreign", depth = 2)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(setOf("mine"), reg.nodes.keys)
    }

    @Test
    fun `agents recorded by a previous plugin run come back without a fresh Task call`() {
        // This is what makes a restart show yesterday's finished agents while still excluding terminal ones.
        agent("old", toolUseId = "toolu_yesterday")
        val reg = registry()
        reg.preAdmit(listOf("old"))
        assertEquals(listOf("old"), reg.scan())
    }

    @Test
    fun `a settled agent keeps its tab and gains its status`() {
        agent("mine", toolUseId = "toolu_ours", text = "work")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        reg.observeSettled("toolu_ours", AgentStatus.FAILED)
        reg.scan()
        // It stays: reading WHY an agent failed is the case this whole feature came from.
        assertEquals(AgentStatus.FAILED, reg.nodes.getValue("mine").status)
    }

    @Test
    fun `a subagent ends when the agent that spawned it ends`() {
        // THE BUG: a nested agent has no toolUseId of its own — nothing can ever settle it — so every level
        // below the first sat RUNNING for ever, pulsing in the tab bar and the diagram after the work ended.
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", parent = "a1", depth = 2)
        agent("a3", parent = "a2", depth = 3)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("a3").status)
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()
        // Down the whole chain: a subagent cannot outlive the turn that spawned it.
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a2").status)
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a3").status)
    }

    @Test
    fun `an agent launched in a RESTORED chat is running, not cut off`() {
        // THE BUG, reported live: `restoring` is set when a chat comes back from disk and is never cleared —
        // it is what admits that chat's own subagents. So every agent launched AFTERWARDS in that chat fell
        // into the "belongs to a previous run" branch and came up STOPPED, which the UI paints RED. Restoring
        // open chats is the default, so this was every agent in a freshly reopened IDE.
        agent("old", depth = 1) // was on disk before the restore, nobody watched it start
        val reg = registry()
        reg.markRestored()
        reg.scan()
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("old").status)

        agent("fresh", toolUseId = "toolu_now", depth = 1)
        agent("fresh-child", parent = "fresh", depth = 2)
        reg.observeSpawn("toolu_now") // we watched THIS one start
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("fresh").status)
        // And its subagent belongs to that same live turn.
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("fresh-child").status)
        // The old one is untouched by any of it.
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("old").status)
    }

    @Test
    fun `a restored agent that finished is not painted as a failure`() {
        // THE BUG, reported live after a restart: a settled status is per-process memory, so restoring a chat
        // left the plugin knowing nothing about its agents — and calling all of them "cut off" turned every
        // agent of every past session RED. That does not merely look wrong: red ASSERTS THAT THEY FAILED,
        // and most had finished perfectly. The binary had already written the answer down.
        agentEnding("finished", "end_turn") // said its piece and stopped
        agentEnding("midflight", "tool_use") // waiting on a tool that never came back
        agentEnding("unanswered", null) // handed a result it never answered
        agent("nothing-written") // meta on disk, transcript not there yet
        val reg = registry()
        reg.markRestored()
        reg.scan()
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("finished").status)
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("midflight").status)
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("unanswered").status)
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("nothing-written").status)
    }

    @Test
    fun `a live agent finishes without a task_notification ever arriving`() {
        // THE BUG, reported live: an agent stayed RUNNING for the rest of the session even though it had
        // plainly finished, and so did the Task card standing for it in the transcript — ClaudeSession hands
        // that card's state to the agent, so the two symptoms are one cause. The only thing that could ever
        // end an agent was its `task_notification`, and the binary does not emit one that this can key on for
        // every shape of agent it runs (several call sites carry no `tool_use_id` at all). Meanwhile the
        // answer was already on disk and already parsed: the agent's own transcript, which the LIVE path
        // never looked at.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, deliveredResult)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("mine").status)

        writeTranscript("mine", openTurn, deliveredResult, closedTurn)
        reg.scan()
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("mine").status)
    }

    @Test
    fun `an agent we watched start is never painted as cut off`() {
        // The asymmetry that keeps the live reading separate from the restore one. The same unfinished
        // transcript means "cut off" for a RESTORED agent, whose process is gone, and "still working" for one
        // we watched start in this session. Collapsing them is how a reopened IDE painted live agents red.
        agent("live", toolUseId = "toolu_ours")
        writeTranscript("live", openTurn)
        agentEnding("restored", "tool_use")
        val reg = registry()
        reg.markRestored()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("live").status)
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("restored").status)
    }

    @Test
    fun `a subagent with its own ending keeps it, whatever the parent did`() {
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", toolUseId = "toolu_child", parent = "a1", depth = 2)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.observeSettled("toolu_child", AgentStatus.FAILED)
        reg.scan()
        assertEquals(AgentStatus.FAILED, reg.nodes.getValue("a2").status)
    }

    @Test
    fun `scan reports only newly admitted agents`() {
        agent("a1", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        assertEquals(listOf("a1"), reg.scan())
        // Nothing new on a re-scan: the caller uses this to blink and notify exactly once per agent.
        assertTrue(reg.scan().isEmpty())
        agent("a2", parent = "a1", depth = 2)
        assertEquals(listOf("a2"), reg.scan())
    }

    @Test
    fun `a missing transcript or directory is not an error`() {
        agent("mine", toolUseId = "toolu_ours") // meta written, jsonl not yet
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertTrue(reg.nodes.getValue("mine").entries.isEmpty())
        assertFalse(reg.nodes.isEmpty())
        // A session that never spawned an agent has no directory at all.
        assertTrue(AgentRegistry(subagentsDir = { null }).scan().isEmpty())
    }

    @Test
    fun `an agent settled live is stamped when it stopped, not when it is scanned`() {
        // The instant belongs to the ending, and scans happen whenever the directory is re-read: stamping at
        // scan time would make every agent look as if it had just finished.
        agent("mine", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        clock = 1_000_060_000L
        reg.scan()

        assertEquals(1_000_000_000L, reg.nodes.getValue("mine").completedAtMillis)
    }

    @Test
    fun `a repeated task_notification does not move the stop instant`() {
        // `task_notification` repeats for the same Task call. A second write would rejuvenate an agent that
        // stopped minutes ago, so an ending is sealed once and never rewritten.
        agent("mine", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        clock = 1_000_300_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()

        assertEquals(1_000_000_000L, reg.nodes.getValue("mine").completedAtMillis)
    }

    @Test
    fun `re-scanning rebuilds the same stop instant`() {
        agent("mine", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        clock = 1_000_060_000L
        reg.scan()
        clock = 1_000_600_000L
        reg.scan()

        assertEquals(1_000_000_000L, reg.nodes.getValue("mine").completedAtMillis)
    }

    @Test
    fun `a nested subagent inherits its parent's stop instant`() {
        // It has no toolUseId of its own, so nothing can ever stamp it directly — and it cannot outlive the
        // turn that spawned it, so the parent's instant is its own.
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", parent = "a1", depth = 2)
        agent("a3", parent = "a2", depth = 3)
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        clock = 1_000_120_000L
        reg.scan()

        assertEquals(1_000_000_000L, reg.nodes.getValue("a2").completedAtMillis)
        assertEquals(1_000_000_000L, reg.nodes.getValue("a3").completedAtMillis)
    }

    @Test
    fun `a running agent has no stop instant`() {
        agent("mine", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `an agent that arrives already finished is stamped when this run started`() {
        // Its status comes off a file left by a previous run, so nobody here watched it stop. Leaving it
        // unstamped would put it outside the retention window for good; stamping it at admission means it is
        // there when the IDE reopens and ages out like everything else.
        agentEnding("finished", "end_turn")
        val reg = registry()
        reg.markRestored()

        reg.scan()

        val node = reg.nodes.getValue("finished")
        assertEquals(AgentStatus.COMPLETED, node.status)
        assertEquals(900_000_000L, node.completedAtMillis)
    }

    @Test
    fun `an agent restored with nothing to judge is stamped when this run started`() {
        agent("old", depth = 1)
        val reg = registry()
        reg.markRestored()

        reg.scan()

        val node = reg.nodes.getValue("old")
        assertEquals(AgentStatus.STOPPED, node.status)
        assertEquals(900_000_000L, node.completedAtMillis)
    }

    @Test
    fun `agents that come back in the same run share one instant`() {
        // One instant for the whole run, not a reading per agent: otherwise two agents restored together
        // vanish from the view at different moments, for no reason anybody could name.
        agentEnding("first", "end_turn")
        agentEnding("second", "tool_use")
        agent("third", depth = 1)
        val reg = registry()
        reg.markRestored()

        clock = 1_000_000_000L
        reg.scan()

        assertEquals(900_000_000L, reg.nodes.getValue("first").completedAtMillis)
        assertEquals(900_000_000L, reg.nodes.getValue("second").completedAtMillis)
        assertEquals(900_000_000L, reg.nodes.getValue("third").completedAtMillis)
    }

    @Test
    fun `the admission stamp survives later scans`() {
        // Re-reading the directory is not an event in an agent's life: a stamp already written stays written,
        // however many times the tree is rebuilt afterwards.
        agentEnding("finished", "end_turn")
        val reg = registry()
        reg.markRestored()

        reg.scan()
        clock = 1_000_600_000L
        reg.scan()
        clock = 1_003_600_000L
        reg.scan()

        assertEquals(900_000_000L, reg.nodes.getValue("finished").completedAtMillis)
    }

    @Test
    fun `an agent whose transcript grows past its ending is running again`() {
        // THE BUG: once an agent showed finished, nothing could ever say otherwise. `statusByToolUse` answered
        // first and nothing removed from it, so a resumed agent — writing records again after a turn it had
        // closed — stayed green for the rest of the session while it was plainly working.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()

        writeTranscript("mine", openTurn, closedTurn, deliveredResult)
        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `a reopened agent stays reopened while its transcript stands still`() {
        // Nothing may oscillate: the same file read three times is one answer, not an alternating one.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()
        writeTranscript("mine", openTurn, closedTurn, deliveredResult)
        reg.scan()

        clock = 1_000_600_000L
        reg.scan()
        clock = 1_003_600_000L
        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `an agent that settled and never writes again keeps its ending and its instant`() {
        // THE LOAD-BEARING ONE. Reopening on evidence of growth is worth nothing if a re-read counts as growth:
        // that turns the fix into the opposite bug, where no agent ever stays finished and every ending shows as
        // live work for ever. A scan is not an event in an agent's life.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)

        clock = 1_000_060_000L
        reg.scan()
        clock = 1_000_600_000L
        reg.scan()
        clock = 1_003_600_000L
        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.COMPLETED, node.status)
        assertEquals(1_000_000_000L, node.completedAtMillis)
    }

    @Test
    fun `blank lines appended after an ending are not growth`() {
        // A line-oriented file ends with a newline, and the binary may add one whenever it likes. Counting that
        // as a further record would reopen every finished agent on disk at the next scan.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()

        writeTranscript("mine", openTurn, closedTurn, "", "   ")
        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.COMPLETED, node.status)
        assertEquals(1_000_000_000L, node.completedAtMillis)
    }

    @Test
    fun `the first scan of a settled agent reopens nothing, however long its transcript`() {
        // The evidence of growth is a comparison, so the first pass has nothing to compare against: it records
        // the baseline and reopens nobody. Otherwise an agent whose file was already long — every restored
        // chat — would be declared resumed the moment the plugin first looked at it.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", closedTurn, deliveredResult, openTurn, deliveredResult)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)

        clock = 1_000_060_000L
        reg.scan()

        val node = reg.nodes.getValue("mine")
        assertEquals(AgentStatus.COMPLETED, node.status)
        assertEquals(1_000_000_000L, node.completedAtMillis)
    }

    @Test
    fun `a restored agent whose transcript went past a closed turn is running`() {
        // Nobody here watched it, so its own transcript is the only evidence — and it says the agent closed a
        // turn and then wrote again. That is a live agent, not one to paint red.
        agent("resumed", depth = 1)
        writeTranscript("resumed", closedTurn, deliveredResult)
        val reg = registry()
        reg.markRestored()

        clock = 1_000_000_000L
        reg.scan()

        val node = reg.nodes.getValue("resumed")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `a restored agent that only ever ended mid-turn is still cut off`() {
        // The other half of the same rule, and red is the right answer here: this work was genuinely cut off.
        agent("midflight", depth = 1)
        writeTranscript("midflight", deliveredResult, openTurn)
        val reg = registry()
        reg.markRestored()

        reg.scan()

        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("midflight").status)
    }

    @Test
    fun `a nested subagent reopens with the parent whose transcript grew`() {
        // It has no toolUseId, so nothing can reopen it directly: it follows its parent, including back into
        // life. Parents are resolved first in a scan, so this happens in the same pass rather than the next.
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", parent = "a1", depth = 2)
        writeTranscript("a1", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()

        writeTranscript("a1", openTurn, closedTurn, deliveredResult)
        reg.scan()

        val node = reg.nodes.getValue("a2")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `reopening admits nobody who was not ours already`() {
        // Admission is the rule that keeps a session resumed from the terminal out of the tab bar — 84 agents in
        // one real session. A growing transcript is evidence about an agent's liveness, never about its owner.
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn, closedTurn)
        agent("foreign", toolUseId = "toolu_terminal")
        writeTranscript("foreign", openTurn, closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        clock = 1_000_000_000L
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()

        writeTranscript("mine", openTurn, closedTurn, deliveredResult)
        writeTranscript("foreign", openTurn, closedTurn, deliveredResult)
        reg.scan()

        assertEquals(setOf("mine"), reg.nodes.keys)
    }
}
