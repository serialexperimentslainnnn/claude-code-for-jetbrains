package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
        Files.writeString(dir.resolve("${AgentMeta.FILE_PREFIX}$id${AgentMeta.META_SUFFIX}"), meta)
        if (text != null) {
            val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
            Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), line)
        }
    }

    private var clock = 1_000_000_000L

    private val runStarted = 900_000_000L

    private fun registry() = AgentRegistry(subagentsDir = { dir }, now = { clock }, runStartedAtMillis = runStarted)

    private fun agentEnding(id: String, stopReason: String?) {
        agent(id, depth = 1)
        val line = if (stopReason == null) {
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""
        } else {
            """{"type":"assistant","message":{"role":"assistant","stop_reason":"$stopReason","content":[]}}"""
        }
        Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), line)
    }

    private val closedTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn","content":[]}}"""

    private val openTurn = """{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use","content":[]}}"""

    private val deliveredResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"x"}]}}"""

    private fun writeTranscript(id: String, vararg lines: String) {
        Files.writeString(dir.resolve(AgentMeta.transcriptFile(id)), lines.joinToString("\n"))
    }

    @Test
    fun `an agent whose Task call we never saw is not shown`() {
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
        assertEquals(AgentStatus.COMPLETED, node.status)
        assertEquals(1, node.entries.size)
        assertEquals("hello from the agent", node.entries.first().text)
    }

    @Test
    fun `admission is inherited down the chain, however deep`() {
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
        agent("old", toolUseId = "toolu_yesterday")
        val reg = registry()
        reg.preAdmit(listOf("old"))
        assertEquals(listOf("old"), reg.scan())
    }

    @Test
    fun `a settled agent keeps its tab and gains its status`() {
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", openTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        reg.observeSettled("toolu_ours", AgentStatus.FAILED)
        reg.scan()
        assertEquals(AgentStatus.FAILED, reg.nodes.getValue("mine").status)
    }

    @Test
    fun `a subagent ends when the agent that spawned it ends`() {
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        agent("a2", parent = "a1", depth = 2)
        agent("a3", parent = "a2", depth = 3)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("a3").status)
        reg.observeSettled("toolu_ours", AgentStatus.COMPLETED)
        reg.scan()
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a2").status)
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a3").status)
    }

    @Test
    fun `an agent launched in a RESTORED chat is running, not cut off`() {
        agent("old", depth = 1)
        val reg = registry()
        reg.markRestored()
        reg.scan()
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("old").status)

        agent("fresh", toolUseId = "toolu_now", depth = 1)
        agent("fresh-child", parent = "fresh", depth = 2)
        reg.observeSpawn("toolu_now")
        reg.scan()
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("fresh").status)
        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("fresh-child").status)
        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("old").status)
    }

    @Test
    fun `a restored agent that finished is not painted as a failure`() {
        agentEnding("finished", "end_turn")
        agentEnding("midflight", "tool_use")
        agentEnding("unanswered", null)
        agent("nothing-written")
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
    fun `a subagent that finished under a parent still working is not reported as running`() {
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        writeTranscript("a1", openTurn)
        agent("a2", parent = "a1", depth = 2)
        writeTranscript("a2", closedTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")

        clock = 1_000_500_000L
        reg.scan()

        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("a1").status)
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a2").status)
        assertEquals(1_000_500_000L, reg.nodes.getValue("a2").completedAtMillis)

        clock = 1_000_900_000L
        reg.scan()
        assertEquals(1_000_500_000L, reg.nodes.getValue("a2").completedAtMillis)
    }

    @Test
    fun `a subagent still working under a working parent stays running`() {
        agent("a1", toolUseId = "toolu_ours", depth = 1)
        writeTranscript("a1", openTurn)
        agent("a2", parent = "a1", depth = 2)
        writeTranscript("a2", openTurn)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()

        val node = reg.nodes.getValue("a2")
        assertEquals(AgentStatus.RUNNING, node.status)
        assertNull(node.completedAtMillis)
    }

    @Test
    fun `scan reports only newly admitted agents`() {
        agent("a1", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        assertEquals(listOf("a1"), reg.scan())
        assertTrue(reg.scan().isEmpty())
        agent("a2", parent = "a1", depth = 2)
        assertEquals(listOf("a2"), reg.scan())
    }

    @Test
    fun `a missing transcript or directory is not an error`() {
        agent("mine", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertTrue(reg.nodes.getValue("mine").entries.isEmpty())
        assertFalse(reg.nodes.isEmpty())
        assertTrue(AgentRegistry(subagentsDir = { null }).scan().isEmpty())
    }

    @Test
    fun `an agent settled live is stamped when it stopped, not when it is scanned`() {
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
    fun `a completed transcript keeps the instant sealed when the stream saw it end`() {
        agent("mine", toolUseId = "toolu_ours")
        writeTranscript("mine", closedTurn)
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
        agent("midflight", depth = 1)
        writeTranscript("midflight", deliveredResult, openTurn)
        val reg = registry()
        reg.markRestored()

        reg.scan()

        assertEquals(AgentStatus.STOPPED, reg.nodes.getValue("midflight").status)
    }

    @Test
    fun `a nested subagent reopens with the parent whose transcript grew`() {
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

    private fun setModified(id: String, millis: Long) {
        Files.setLastModifiedTime(
            dir.resolve(AgentMeta.transcriptFile(id)),
            java.nio.file.attribute.FileTime.fromMillis(millis),
        )
    }

    @Test
    fun `an unchanged transcript is not read again`() {
        agent("a", toolUseId = "toolu_ours")
        writeTranscript("a", closedTurn)
        setModified("a", 1_700_000_000_000L)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a").status)

        writeTranscript("a", openTurn)
        setModified("a", 1_700_000_000_000L)
        reg.scan()

        assertEquals(
            AgentStatus.COMPLETED,
            reg.nodes.getValue("a").status,
            "same size and same mtime: the file must not have been read a second time",
        )
    }

    @Test
    fun `a transcript whose stamp moved is read again`() {
        agent("a", toolUseId = "toolu_ours")
        writeTranscript("a", closedTurn)
        setModified("a", 1_700_000_000_000L)
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a").status)

        writeTranscript("a", openTurn)
        setModified("a", 1_700_000_060_000L)
        reg.scan()

        assertEquals(AgentStatus.RUNNING, reg.nodes.getValue("a").status, "a newer mtime must force the read")
    }

    @Test
    fun `an agent whose transcript appears later is read the moment it does`() {
        agent("a", toolUseId = "toolu_ours")
        val reg = registry()
        reg.observeSpawn("toolu_ours")
        reg.scan()
        assertTrue(reg.nodes.getValue("a").entries.isEmpty())

        writeTranscript("a", closedTurn)
        reg.scan()

        assertEquals(AgentStatus.COMPLETED, reg.nodes.getValue("a").status)
    }
}
