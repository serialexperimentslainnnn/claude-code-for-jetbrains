package dev.lain.claudejb.session

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PluginAgentIndexMigrationTest {

    @TempDir
    lateinit var home: Path

    private var previousHome: String? = null

    @BeforeEach
    fun redirectHome() {
        previousHome = PluginAgentIndex.homeOverride
        PluginAgentIndex.homeOverride = home.toString()
    }

    @AfterEach
    fun restoreHome() {
        PluginAgentIndex.homeOverride = previousHome
    }

    private fun file(): Path = home.resolve("ide").resolve("claude-code-native").resolve("agent-index.json")

    private fun writeIndex(json: String) {
        Files.createDirectories(file().parent)
        Files.writeString(file(), json)
    }

    private fun node(id: String, parent: String? = null, type: String = PluginAgentIndex.Kind.AGENT) =
        AgentNode(AgentMeta(agentId = id, agentType = "general-purpose", parentAgentId = parent))
            .also { require(type.isNotBlank()) }

    @Test
    fun `a legacy v1 file is read, not lost`() {
        writeIndex("""{"s1":[{"agentId":"agent-a6798878f17f074e4","open":true,"closedByUser":false}]}""")
        val index = PluginAgentIndex()
        assertEquals(listOf("a6798878f17f074e4"), index.admittedAgents("s1"))
        assertEquals(listOf("a6798878f17f074e4"), index.openAgents("s1"))
        val admitted = index.admittedAgents("s1")
        assertTrue(AgentMeta.bareAgentId("a6798878f17f074e4") in admitted)
        assertTrue(AgentMeta.bareAgentId("agent-a6798878f17f074e4") in admitted)
    }

    @Test
    fun `the migrated file is rewritten once, in the current shape`() {
        writeIndex("""{"s1":[{"agentId":"agent-abc","open":true,"closedByUser":false}]}""")
        PluginAgentIndex().admittedAgents("s1")
        val body = Files.readString(file())
        assertTrue(body.contains("\"version\": ${PluginAgentIndex.FORMAT_VERSION}"), body)
        assertTrue(body.contains("\"id\": \"abc\""), body)
        assertFalse(body.contains("agent-abc"), "the legacy id shape must not survive the rewrite: $body")
    }

    @Test
    fun `a v1 close still sticks after the migration`() {
        writeIndex("""{"s1":[{"agentId":"abc","open":false,"closedByUser":true}]}""")
        val index = PluginAgentIndex()
        assertEquals(listOf("abc"), index.admittedAgents("s1"))
        assertTrue(index.openAgents("s1").isEmpty())
    }

    @Test
    fun `admitting records the whole shape, and a subagent says so`() {
        val index = PluginAgentIndex()
        index.admit("s1", node("a1"))
        index.admit("s1", node("a2", parent = "a1"))
        val nodes = index.nodes("s1")
        assertEquals(PluginAgentIndex.Kind.AGENT, nodes.first { it.id == "a1" }.type)
        val child = nodes.first { it.id == "a2" }
        assertEquals(PluginAgentIndex.Kind.SUBAGENT, child.type)
        assertEquals(PluginAgentIndex.Ref(PluginAgentIndex.Kind.AGENT, "a1"), child.parent)
        assertEquals(PluginAgentIndex.Kind.CHAT, nodes.first { it.id == "a1" }.parent?.type)
    }

    @Test
    fun `a background task is recorded with its launching call and its owner`() {
        val index = PluginAgentIndex()
        index.admit("s1", node("a1"))
        index.recordTask("s1", "t1", toolUseId = "toolu_x", ownerAgentId = "a1")
        val task = index.nodes("s1").first { it.id == "t1" }
        assertEquals(PluginAgentIndex.Kind.TASK, task.type)
        assertEquals("toolu_x", task.toolUseId)
        assertEquals(PluginAgentIndex.Ref(PluginAgentIndex.Kind.AGENT, "a1"), task.parent)
        assertEquals(listOf("t1"), index.taskIds("s1"))
    }

    @Test
    fun `a task with no known owner hangs off the chat rather than being guessed`() {
        val index = PluginAgentIndex()
        index.recordTask("s1", "t1", toolUseId = null, ownerAgentId = null)
        assertEquals(PluginAgentIndex.Kind.CHAT, index.nodes("s1").single().parent?.type)
    }

    @Test
    fun `re-admitting an agent does not reopen a tab the user closed`() {
        val index = PluginAgentIndex()
        index.admit("s1", node("a1"))
        index.setTabOpen("s1", "agent-a1", false)
        index.admit("s1", node("a1"))
        assertTrue(index.openAgents("s1").isEmpty())
        assertEquals(listOf("a1"), index.admittedAgents("s1"))
    }

    @Test
    fun `the record survives a reload`() {
        PluginAgentIndex().apply {
            admit("s1", node("a1"))
            admit("s1", node("a2", parent = "a1"))
            recordTask("s1", "t1", "toolu_x", "a2")
        }
        val reloaded = PluginAgentIndex()
        assertEquals(listOf("a1", "a2"), reloaded.admittedAgents("s1"))
        assertEquals(listOf("t1"), reloaded.taskIds("s1"))
        assertEquals(
            PluginAgentIndex.Ref(PluginAgentIndex.Kind.SUBAGENT, "a2"),
            reloaded.nodes("s1").first { it.id == "t1" }.parent,
        )
    }
}
