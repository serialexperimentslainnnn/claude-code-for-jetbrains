package dev.lain.claudejb.session

import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentIndexPrivacyTest {

    private fun index(vararg nodes: PluginAgentIndex.Node) =
        mapOf("5f2b-session" to PluginAgentIndex.SessionRecord(nodes.toList()))

    @Test
    fun `the persisted form carries the tree, never the content`() {
        val encoded = PluginAgentIndex.encode(
            index(
                PluginAgentIndex.Node(
                    type = PluginAgentIndex.Kind.AGENT,
                    id = "a1",
                    parent = PluginAgentIndex.Ref(PluginAgentIndex.Kind.CHAT, "5f2b-session"),
                    agentType = "general-purpose",
                ),
                PluginAgentIndex.Node(
                    type = PluginAgentIndex.Kind.SUBAGENT,
                    id = "a2",
                    parent = PluginAgentIndex.Ref(PluginAgentIndex.Kind.AGENT, "a1"),
                ),
                PluginAgentIndex.Node(
                    type = PluginAgentIndex.Kind.TASK,
                    id = "t1",
                    parent = PluginAgentIndex.Ref(PluginAgentIndex.Kind.AGENT, "a1"),
                    toolUseId = "toolu_x",
                ),
            ),
        )
        assertTrue(encoded.contains("\"type\": \"subagent\""), encoded)
        assertTrue(encoded.contains("\"type\": \"backgroundtask\""), encoded)
        assertTrue(encoded.contains("\"parent\""))
        assertTrue(encoded.contains("\"childs\""))
        setOf("description", "prompt", "transcript", "summary", "stdout")
            .forEach { assertFalse(encoded.contains(it), "persisted index must not carry '$it'") }
    }

    @Test
    fun `children are derived from the parents, so the two cannot disagree`() {
        val encoded = PluginAgentIndex.encode(
            index(
                PluginAgentIndex.Node(PluginAgentIndex.Kind.AGENT, "a1"),
                PluginAgentIndex.Node(
                    PluginAgentIndex.Kind.SUBAGENT,
                    "a2",
                    parent = PluginAgentIndex.Ref(PluginAgentIndex.Kind.AGENT, "a1"),
                ),
            ),
        )
        val back = PluginAgentIndex.decode(encoded).getValue("5f2b-session")
        val parent = back.nodes.first { it.id == "a1" }
        assertEquals(listOf(PluginAgentIndex.Ref(PluginAgentIndex.Kind.SUBAGENT, "a2")), parent.childs)
    }

    @Test
    fun `a round trip preserves the tree and the tab state`() {
        val original = index(
            PluginAgentIndex.Node(
                type = PluginAgentIndex.Kind.AGENT,
                id = "a",
                parent = PluginAgentIndex.Ref(PluginAgentIndex.Kind.CHAT, "5f2b-session"),
                agentType = "explorer",
                open = false,
                closedByUser = true,
            ),
        )
        val back = PluginAgentIndex.decode(PluginAgentIndex.encode(original)).getValue("5f2b-session")
        val node = back.nodes.single()
        assertEquals("a", node.id)
        assertEquals("explorer", node.agentType)
        assertFalse(node.open)
        assertTrue(node.closedByUser)
    }

    @Test
    fun `corrupt or blank state never throws`() {
        assertTrue(PluginAgentIndex.decode("").isEmpty())
        assertTrue(PluginAgentIndex.decode("{not json").isEmpty())
    }

    @Test
    fun `the index lives in the IDE's safe, and nothing writes it to a file`() {
        assertTrue(SettingsScope("abc123").agentIndexName.startsWith(SecretStore.AGENT_INDEX + "@"))

        val source = File("src/main/kotlin/dev/lain/claudejb/session/PluginAgentIndex.kt")
        assertTrue(source.isFile, "the index moved: this contract has to move with it")
        val code = source.readLines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
            .joinToString("\n")
        listOf("Files.write", "writeText", "FileWriter").forEach { writing ->
            assertFalse(writing in code, "`$writing` would put the index back on disk in the clear")
        }
    }
}
