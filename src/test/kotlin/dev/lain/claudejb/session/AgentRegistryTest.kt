package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun registry() = AgentRegistry(subagentsDir = { dir })

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
        assertEquals(AgentStatus.RUNNING, node.status)
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
}
