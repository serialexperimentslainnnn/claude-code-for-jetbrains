package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * What is RUNNING, for the dashboard: the agent tree, the background tasks, and the Workloads diagram that
 * draws both across every open chat. Part of [JcefSessionData]'s payload; see there for the whole shape.
 */
internal object JcefWorkloadData {

    /**
     * The agent tree for the Agents / Subagents windows:
     * `[{ agentId, label, type, status, depth, parent, chain, running }]`, empty when this chat has none.
     *
     * [chain] is the ownership line the user asked to see — `Chat |_ Agent A |_ Agent B` — built here rather
     * than in the frontend because the parentage is a property of the data, not of how it is drawn, and the
     * same string is what the Background tasks window shows for the task's owner.
     *
     * Every row carries its `agentId`, which is what the window's link sends back to jump to that tab.
     *
     * ---
     * `[{chatId, title, selected, tree:[…], tasks:[…]}]` — one entry per open chat.
     *
     * Each entry reuses the very same builders the single-session payload uses, so a node means the same
     * thing whichever chat it came from and there is no second serialisation to drift.
     *
     * ONE ENTRY PER SESSION. A tab pinned to a subagent is a second tab over the same panel — a view of one
     * agent's transcript, not another workload — so the strip lists that session twice and the diagram drew
     * the chat, its agents and its tasks twice over. Keyed by session identity, first tab wins (the chat's
     * own tab comes before anything pinned out of it).
     */
    fun workloadsJson(workloads: List<JcefSessionData.Workload>) = buildJsonArray {
        workloads.distinctBy { it.session }.forEach { w ->
            addJsonObject {
                put("chatId", w.chatId)
                put("title", w.title)
                put("selected", w.selected)
                put("tree", agentTreeJson(w.session))
                put("tasks", backgroundTasksJson(w.session))
            }
        }
    }

    fun agentTreeJson(session: ClaudeSession) = buildJsonArray {
        val nodes = session.runningAgents.nodes
        nodes.values.forEach { node ->
            addJsonObject {
                put("agentId", node.agentId)
                put("label", node.meta.label())
                put("type", node.meta.agentType)
                put("status", JcefStatus.of(node.status))
                put("depth", node.depth)
                put("parent", node.parentAgentId)
                put("chain", ownershipChain(session.title, node.agentId, nodes))
                put("running", node.status == dev.lain.claudejb.session.AgentStatus.RUNNING)
            }
        }
    }

    /**
     * `Chat |_ Agent A |_ Agent B` for [agentId], walking up `parentAgentId`.
     *
     * Guarded against a cycle by construction: the walk stops at the first id it has already seen. The binary
     * writes these parent links, and a malformed one must degrade to a shorter chain, never to a hang.
     */
    private fun ownershipChain(
        chatTitle: String,
        agentId: String,
        nodes: Map<String, dev.lain.claudejb.session.AgentNode>,
    ): String {
        val parts = ArrayDeque<String>()
        val seen = HashSet<String>()
        var current: String? = agentId
        while (current != null && seen.add(current)) {
            val node = nodes[current] ?: break
            parts.addFirst(node.meta.label())
            current = node.parentAgentId
        }
        parts.addFirst(chatTitle)
        // A breadcrumb, not a tree: it is one line naming a path, so it reads with a chevron. The tree
        // glyphs belong to the tab rows, where there really are branches to draw — repeating `|_` here made
        // a single line look like a broken diagram.
        return parts.joinToString("  ›  ")
    }

    // NB `subagentsJson` lived here until 5.5.0. The Session view no longer carries agent data at all: the
    // Agents / Subagents windows read the real tree from the binary's per-agent files, and keeping a second
    // list built from the task event stream would have meant two views of the same agents that can disagree.
    // `ClaudeSession.subagentTasks` is still used — it is what resolves a background task's owning agent —
    // but it is no longer a thing the dashboard draws.

    /**
     * One row per live background task: `{ id, desc, type }`; empty array when none. Sourced from the
     * `background_tasks_changed` LEVEL signal, so it always reflects the *current* set — it can't wedge on a
     * missed edge the way the subagent list can, and it is deliberately not correlated with it.
     */
    fun backgroundTasksJson(session: ClaudeSession) = buildJsonArray {
        val nodes = session.runningAgents.nodes
        // The plugin's own record rather than the binary's live set, and for one reason: that set is a LEVEL
        // signal, so a task that finished simply stops being listed — its row vanished from this window the
        // instant it ended, taking its output with it. The registry keeps finished tasks, marked as such, and
        // already excludes agents (to the binary a running agent IS a background task, which is how this
        // window used to duplicate the Agents one).
        session.backgroundTaskRegistry.all.forEach { task ->
            // ONE owner-resolution rule, owned by the session, so this window and the tab rows cannot
            // disagree about who launched a task. Unresolvable means unclaimed: the row says the chat and
            // stops there, because an invented chain is worse than an honest gap.
            val owner = session.ownerAgentOfTask(task.taskId)
            addJsonObject {
                put("id", task.taskId)
                put("desc", task.description)
                put("type", task.taskType)
                put("running", task.running)
                put("status", JcefStatus.of(task.running))
                put("agentId", owner)
                put("chain", owner?.let { ownershipChain(session.title, it, nodes) } ?: session.title)
            }
        }
    }
}
