package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What [PluginAgentIndex] is allowed to persist, pinned as a contract.
 *
 * Two rules, both from the user. **Nothing goes into the project's `.idea/`**: it is shared, gets committed
 * by accident and is routinely synced, so anything there is effectively published — the index lives under
 * `~/.claude`, private to the user and where this data already is. And it records the **shape** (what each
 * node is, what it hangs off, what hangs off it) but never the **content**: an agent's description
 * ("Translate the SAP standards") already says what the user is working on, and a prompt or a transcript says
 * far more. Those are read from the binary's own files on demand, so a copy here would buy nothing and create
 * a second thing to leak or go stale.
 *
 * This test exists to stop a future "just cache the title so the tab restores faster" from quietly turning an
 * index into a data store.
 */
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
        // The shape is stated, so the file can be read and checked on its own.
        assertTrue(encoded.contains("\"type\": \"subagent\""), encoded)
        assertTrue(encoded.contains("\"type\": \"backgroundtask\""), encoded)
        assertTrue(encoded.contains("\"parent\""))
        assertTrue(encoded.contains("\"childs\""))
        // The content is not.
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
        // Never stored as an independently-editable field: a hand-maintained child list is a second source of
        // truth, and its first bug is a node claiming a child that no longer exists.
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
    fun `the index lives under the user's claude home, never in the project`() {
        // The location IS the privacy decision, so it is pinned rather than left to a comment.
        val home = PluginAgentIndex.homeOverride
        assertTrue(home != null && home.endsWith("/.claude"), "expected ~/.claude, got $home")
    }
}
