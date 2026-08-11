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
    fun `a tab carries the label and nothing else`() {
        // The tree is drawn by the ROW's header, as icons (TreeBranchIcon). It used to be repeated as box
        // characters on every tab too, which drew the same tree twice in two different styles — "|_ Mapa de
        // tests" sitting inside a row already headed by a fork. Tabs are siblings inside their row; what
        // they hang off is what the header says.
        assertEquals("Translate the SAP sta…", AgentTabLabels.tab(node(), relativeDepth = 1))
        assertEquals("Translate the SAP sta…", AgentTabLabels.tab(node(), relativeDepth = 4))
    }

    @Test
    fun `the label is truncated to the same width the chat tabs use`() {
        val deep = AgentTabLabels.tab(node("Short"), relativeDepth = 12)
        assertEquals("Short", deep)
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
        assertEquals("agent-xyz", AgentTabLabels.tab(anonymous))
    }
}
