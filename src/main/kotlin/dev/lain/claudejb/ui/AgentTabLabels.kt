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

    /**
     * The tab's text: just the agent's label, truncated.
     *
     * **No tree connector here, and that is the fix rather than the omission.** The row's own header already
     * draws the branch (`├─ Agents`, `│  └─ Subagents`), so repeating a connector on every tab drew the tree
     * twice in two different styles — `|_ Mapa de tests` sitting inside a row headed `├─ Agents`. The tabs
     * are siblings inside their row; what they hang off is what the header says.
     *
     * [relativeDepth] is kept in the signature because callers know it and a future compact mode may want
     * it, but it deliberately does not change the text today.
     */
    @Suppress("UNUSED_PARAMETER")
    fun tab(node: AgentNode, relativeDepth: Int = 1): String = truncate(node.meta.label())

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
}
