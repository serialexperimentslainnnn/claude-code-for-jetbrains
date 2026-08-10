package dev.lain.claudejb.ui

import dev.lain.claudejb.session.AgentNode
import dev.lain.claudejb.session.AgentStatus

/**
 * How an agent is written on a tab, in the tree view the user asked for.
 *
 * Pure on purpose: this is the part of the agent strips that has rules worth pinning (indentation, status,
 * truncation, what happens to a nameless agent), and keeping it out of the Swing class is what makes those
 * rules testable without a UI.
 *
 * The shape is `|_ Translate the SAP standards`, with one `|_` per level below the strip's own root, so a
 * subagent of a subagent reads as `|_ |_ …`. It is deliberately the same idiom in the tab strips and in the
 * dashboard lists: one visual language for "this hangs off that".
 */
object AgentTabLabels {

    /** Max characters on a tab before ellipsis — mirrors the chat tabs' own cap so the strips look alike. */
    const val TAB_TITLE_MAX = 22

    /** The tree connector repeated per level of depth below the strip's root. */
    private const val CONNECTOR = "|_ "

    /**
     * The tab's text: tree connector, then the agent's own label, truncated.
     *
     * [relativeDepth] is depth **within the strip**, not the absolute `spawnDepth` — the Subagents strip
     * shows children of the selected agent, so its first level is one connector, not three.
     */
    fun tab(node: AgentNode, relativeDepth: Int = 1): String =
        CONNECTOR.repeat(relativeDepth.coerceIn(1, MAX_CONNECTORS)) + truncate(node.meta.label())

    /**
     * The tooltip: the full label, the agent type, and how it ended.
     *
     * Everything the tab had to drop for width goes here — the full description, and the `agentType`, which
     * is what tells a `general-purpose` agent from a custom one when six tabs share a similar title.
     */
    fun tooltip(node: AgentNode): String = buildString {
        append(node.meta.label())
        node.meta.agentType?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
        append("  ·  ").append(statusText(node.status))
    }

    /** Human wording for a status. A finished agent keeps its tab, so the tab has to say how it finished. */
    fun statusText(status: AgentStatus): String = when (status) {
        AgentStatus.RUNNING -> "running"
        AgentStatus.COMPLETED -> "completed"
        AgentStatus.FAILED -> "failed"
        AgentStatus.STOPPED -> "stopped"
    }

    private fun truncate(s: String): String {
        val clean = s.trim().ifBlank { "Agent" }
        return if (clean.length <= TAB_TITLE_MAX) clean else clean.take(TAB_TITLE_MAX - 1) + "…"
    }

    /** Beyond this the connectors would eat the whole label; deep chains stop indenting, not the tree. */
    private const val MAX_CONNECTORS = 4
}
