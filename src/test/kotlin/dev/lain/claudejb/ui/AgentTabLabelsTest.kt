package dev.lain.claudejb.ui

import dev.lain.claudejb.session.AgentMeta
import dev.lain.claudejb.session.AgentNode
import dev.lain.claudejb.session.AgentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The tree labelling of agent tabs — the rules worth pinning, kept out of Swing so they can be. */
class AgentTabLabelsTest {

    private fun node(
        label: String = "Translate the SAP standards",
        type: String? = "general-purpose",
        status: AgentStatus = AgentStatus.RUNNING,
    ) = AgentNode(AgentMeta("agent-1", agentType = type, description = label), status = status)

    @Test
    fun `a tab shows one connector per level below its strip`() {
        assertEquals("|_ Translate the SAP…", AgentTabLabels.tab(node(), relativeDepth = 1))
        assertTrue(AgentTabLabels.tab(node(), relativeDepth = 2).startsWith("|_ |_ "))
        // Depth within the STRIP, not spawnDepth: the Subagents strip shows children of the selected agent,
        // so its first level is one connector however deep that agent sits in the whole tree.
        assertTrue(AgentTabLabels.tab(node(), relativeDepth = 0).startsWith("|_ "))
    }

    @Test
    fun `a very deep chain stops indenting instead of losing the label`() {
        val deep = AgentTabLabels.tab(node("Short"), relativeDepth = 12)
        assertTrue(deep.endsWith("Short"))
        assertTrue(deep.count { it == '_' } <= 4, "connectors must be capped, got: $deep")
    }

    @Test
    fun `the label is truncated on the tab and complete in the tooltip`() {
        val n = node("A description far longer than any tab is ever going to be")
        assertTrue(AgentTabLabels.tab(n).length <= AgentTabLabels.TAB_TITLE_MAX + 4)
        assertTrue(AgentTabLabels.tab(n).endsWith("…"))
        assertTrue(AgentTabLabels.tooltip(n).contains("far longer than any tab"))
    }

    @Test
    fun `the tooltip carries the agent type and how it ended`() {
        // Six agents with similar descriptions are told apart by their type, and a kept tab has to say
        // whether the agent finished or died -- reading why one failed is the point of keeping it.
        val t = AgentTabLabels.tooltip(node(status = AgentStatus.FAILED))
        assertTrue(t.contains("general-purpose"))
        assertTrue(t.contains("failed"))
    }

    @Test
    fun `a nameless agent still gets a navigable tab`() {
        val anonymous = AgentNode(AgentMeta("agent-xyz"))
        assertEquals("|_ agent-xyz", AgentTabLabels.tab(anonymous))
    }
}
