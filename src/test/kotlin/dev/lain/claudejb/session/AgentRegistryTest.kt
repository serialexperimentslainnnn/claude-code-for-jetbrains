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

    private fun agent(id: String, toolUseId: String? = null, parent: String? = null, depth: Int = 1, text: String? = null) {
        val meta = buildString {
            append("""{"agentType":"general-purpose","description":"Task $id"""")
            toolUseId?.let { append(""","toolUseId":"$it"""") }
            parent?.let { append(""","parentAgentId":"$it"""") }
            append(""","spawnDepth":$depth}""")
        }
        Files.writeString(dir.resolve("$id${AgentMeta.META_SUFFIX}"), meta)
        if (text != null) {
            val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
            Files.writeString(dir.resolve("$id${AgentMeta.TRANSCRIPT_SUFFIX}"), line)
        }
    }

    private fun registry() = AgentRegistry(subagentsDir = { dir })

    @Test
    fun `an agent whose Task call we never saw is not shown`() {
        // THE POINT: these files exist on disk and belong to a terminal run. Showing "whatever is in the
        // directory" is what would reopen a heavy session with dozens of tabs the plugin never spawned.
        agent("agent-foreign", toolUseId = "toolu_terminal")
        val reg = registry()
        assertTrue(reg.scan().isEmpty())
        assertTrue(reg.nodes.isEmpty())
    }

    @Test
    fun `an agent whose Task call we saw is admitted, with its label and transcript`() {
        agent("agent-mine", toolUseId = "toolu_ours", text = "hello from the agent")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        assertEquals(listOf("agent-mine"), reg.scan())
        val node = reg.nodes.getValue("agent-mine")
        assertEquals("Task agent-mine", node.meta.label())
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
        agent("agent-1", toolUseId = "toolu_ours", depth = 1)
        agent("agent-2", parent = "agent-1", depth = 2)
        agent("agent-3", parent = "agent-2", depth = 3)
        agent("agent-4", parent = "agent-3", depth = 4)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(setOf("agent-1", "agent-2", "agent-3", "agent-4"), reg.nodes.keys)
        assertEquals(listOf("agent-2"), reg.children("agent-1").map { it.agentId })
        assertEquals(listOf("agent-1"), reg.children(null).map { it.agentId })
    }

    @Test
    fun `a foreign subtree stays out even when ours is present`() {
        agent("agent-mine", toolUseId = "toolu_ours")
        agent("agent-foreign", toolUseId = "toolu_terminal")
        agent("agent-foreign-child", parent = "agent-foreign", depth = 2)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(setOf("agent-mine"), reg.nodes.keys)
    }

    @Test
    fun `agents recorded by a previous plugin run come back without a fresh Task call`() {
        // This is what makes a restart show yesterday's finished agents while still excluding terminal ones.
        agent("agent-old", toolUseId = "toolu_yesterday")
        val reg = registry()
        reg.preAdmit(listOf("agent-old"))
        assertEquals(listOf("agent-old"), reg.scan())
    }

    @Test
    fun `a settled agent keeps its tab and gains its status`() {
        agent("agent-mine", toolUseId = "toolu_ours", text = "work")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        reg.observeSettled("toolu_ours", AgentStatus.FAILED)
        reg.scan()
        // It stays: reading WHY an agent failed is the case this whole feature came from.
        assertEquals(AgentStatus.FAILED, reg.nodes.getValue("agent-mine").status)
    }

    @Test
    fun `scan reports only newly admitted agents`() {
        agent("agent-1", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        assertEquals(listOf("agent-1"), reg.scan())
        // Nothing new on a re-scan: the caller uses this to blink and notify exactly once per agent.
        assertTrue(reg.scan().isEmpty())
        agent("agent-2", parent = "agent-1", depth = 2)
        assertEquals(listOf("agent-2"), reg.scan())
    }

    @Test
    fun `a missing transcript or directory is not an error`() {
        agent("agent-mine", toolUseId = "toolu_ours") // meta written, jsonl not yet
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertTrue(reg.nodes.getValue("agent-mine").entries.isEmpty())
        assertFalse(reg.nodes.isEmpty())
        // A session that never spawned an agent has no directory at all.
        assertTrue(AgentRegistry(subagentsDir = { null }).scan().isEmpty())
    }
}
