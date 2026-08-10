package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.session.AgentNode
import dev.lain.claudejb.session.AgentRegistry
import java.awt.BorderLayout
import javax.swing.BoxLayout

/**
 * The two agent rows under the chat tabs: `Agents`, then `Subagents`.
 *
 * The rule the whole thing follows is "each row shows the children of the row above's selection":
 *  - `Agents` shows the agents of the **selected chat**, so switching chat swaps the row.
 *  - `Subagents` shows the agents spawned by the **selected agent**, so it changes as you move along the row
 *    above, and an agent that spawned nothing leaves it empty.
 *
 * Both rows are **always visible**, empty or not, so the transcript below never jumps up and down as agents
 * come and go — on a session that spawns them constantly, a row that appears and disappears is worse than an
 * empty one.
 *
 * Selection reports **which transcript to paint** ([onShowTranscript]): the chat's own transcript when
 * nothing is selected in either row, otherwise that agent's. One browser, many transcripts — see
 * [AgentStripPanel].
 */
internal class AgentTabsPanel(project: Project, parent: Disposable) : JBPanel<AgentTabsPanel>(BorderLayout()) {

    private val agents = AgentStripPanel(project, parent, "Agents")
    private val subagents = AgentStripPanel(project, parent, "Subagents")

    /** Told which agent's transcript to show — null means the chat's own. */
    var onShowTranscript: (String?) -> Unit = {}

    /** Told that the user closed an agent's tab, so the close can be remembered across restarts. */
    var onTabClosed: (String) -> Unit = {}

    /** The registry currently rendered, so a selection can re-derive the children rows. */
    private var registry: AgentRegistry? = null

    init {
        val rows = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        rows.add(agents)
        rows.add(subagents)
        add(rows, BorderLayout.CENTER)

        agents.onEvents(
            selected = { id ->
                renderSubagentsOf(id)
                onShowTranscript(id)
            },
            closed = { id ->
                onTabClosed(id)
                // Its children have no row to live in once the parent is gone, and the transcript falls back
                // to the chat's own — otherwise the browser would keep painting a tab that is not there.
                renderSubagentsOf(agents.selectedAgentId)
                onShowTranscript(agents.selectedAgentId)
            },
        )
        subagents.onEvents(
            selected = { id -> onShowTranscript(id ?: agents.selectedAgentId) },
            closed = { id ->
                onTabClosed(id)
                onShowTranscript(subagents.selectedAgentId ?: agents.selectedAgentId)
            },
        )
    }

    /**
     * Re-renders both rows from [registry], hiding the agents whose tab the user closed ([hidden]).
     *
     * A closed tab is not a deleted agent: its transcript is the binary's file and its card is still in the
     * main transcript, which is how it comes back. This method only stops drawing it.
     */
    fun render(registry: AgentRegistry, hidden: Set<String> = emptySet()) {
        this.registry = registry
        val roots = registry.children(null).filterNot { it.agentId in hidden }
        agents.render(roots)
        renderSubagentsOf(agents.selectedAgentId, hidden)
    }

    /** Opens (or re-selects) [agentId]'s tab, wherever in the tree it sits. Used by the transcript card. */
    fun reveal(agentId: String) {
        val reg = registry ?: return
        val node = reg.nodes[agentId] ?: return
        val parent = node.parentAgentId
        if (parent == null) {
            agents.select(agentId)
            return
        }
        // A subagent: select its parent first so the Subagents row is showing the right family, then it.
        agents.select(parent)
        renderSubagentsOf(parent)
        subagents.select(agentId)
    }

    /** Two orange pulses on whichever row now carries [agentId]. */
    fun blink(agentId: String) {
        if (agents.has(agentId)) agents.blink(agentId) else subagents.blink(agentId)
    }

    private fun renderSubagentsOf(parentId: String?, hidden: Set<String> = emptySet()) {
        val reg = registry
        val children: List<AgentNode> = if (reg == null || parentId == null) {
            emptyList()
        } else {
            reg.children(parentId).filterNot { it.agentId in hidden }
        }
        subagents.render(children)
    }
}
