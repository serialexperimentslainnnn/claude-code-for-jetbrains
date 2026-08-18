package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What is RUNNING, for the dashboard: the agent tree, the background tasks, and the Workloads diagram that
 * draws both across every open chat. Part of [JcefSessionData]'s payload; see there for the whole shape.
 *
 * The retention window is applied here, and [visible] is the one place a session is measured against it — the
 * tab bar draws the same two sets, and a second copy of that mapping is how the two views would come to
 * disagree about which finished workload is still listed. [windowJson] is the other half of that: the window
 * is also SENT, with the set of windows the rule will accept, so the view can change what it is judged by
 * without the page holding an opinion about which values exist.
 */
internal object JcefWorkloadData {

    /**
     * What [session] shows under a window of [windowMinutes], as of [nowMillis].
     *
     * The clock is a parameter all the way down: one push resolves it once, so everything painted together
     * ages by the same instant instead of each card reading its own. The owner link is what ties a background
     * task to an agent, so it is resolved here and handed to the rule as that task's parent — which is what
     * keeps a visible task's owner in the payload it refers to.
     */
    fun visible(session: ClaudeSession, windowMinutes: Int, nowMillis: Long): WorkloadWindow.Visible =
        WorkloadWindow.visible(
            agents = session.runningAgents.nodes.values.map {
                WorkloadWindow.Entry(
                    id = it.agentId,
                    parentId = it.parentAgentId,
                    running = it.status == AgentStatus.RUNNING,
                    completedAtMillis = it.completedAtMillis,
                )
            },
            tasks = session.backgroundTaskRegistry.all.map {
                WorkloadWindow.Entry(
                    id = it.taskId,
                    parentId = session.ownerAgentOfTask(it.taskId),
                    running = it.running,
                    completedAtMillis = it.completedAtMillis,
                )
            },
            windowMinutes = windowMinutes,
            nowMillis = nowMillis,
        )

    /**
     * The retention window itself, so the view can offer it: `{ minutes, options:[{ minutes, label }] }`.
     *
     * The choices are SENT, never spelled in the page, and that is the whole reason this exists. The page has
     * no way to know which windows [WorkloadWindow.isVisible] can actually apply, so a list written there
     * would eventually offer a value the rule does not know — and the failure is silent, because an unknown
     * window is simply stored and then measured against nothing the user asked for. Emitting
     * [WorkloadWindow.WINDOW_MINUTES] with [WorkloadWindow.label] makes the offered set and the applied set
     * one set.
     *
     * [WorkloadWindow.ALL] rides in the list as an ordinary entry rather than as a flag: it IS a value of
     * `minutes`, and giving the sentinel a separate shape would mean a branch on both sides of the bridge.
     * NB it is worth `0`, which is falsy in JavaScript — the page compares it with `== null`, never with a
     * truthiness test, or the sentinel becomes the one choice that cannot be shown or selected.
     *
     * [minutes] is echoed back so the control can show what is in force. It is the very value the rest of
     * this payload was filtered with, so the diagram and the control that explains it cannot disagree.
     */
    fun windowJson(minutes: Int): JsonObject = buildJsonObject {
        put("minutes", minutes)
        putJsonArray("options") {
            WorkloadWindow.WINDOW_MINUTES.forEach { option ->
                addJsonObject {
                    put("minutes", option)
                    put("label", WorkloadWindow.label(option))
                }
            }
        }
    }

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
     * `[{chatId, title, selected, tree:[…], tasks:[…]}]` — **one entry per tab handed in, faithfully**.
     *
     * Each entry reuses the very same builders the single-session payload uses, so a node means the same
     * thing whichever chat it came from and there is no second serialisation to drift.
     *
     * **Nothing is deduplicated here, and that is a decision rather than an omission.** This used to key by
     * session identity and keep the first tab, because a subtab pinned as a tab of its own put a SECOND tab
     * over the same session in the strip and the diagram drew that chat, its agents and its tasks twice. The
     * pinned view is gone and the strip now guarantees one tab per session (`ChatTabsPanel`, pinned by
     * `ToolWindowWiringContractTest`), so the filter can no longer fire — and reinstating it would be worse
     * than useless: it would silently absorb a duplicate whose real symptom is invisible too (a close
     * disposing the `claude` process another tab is painting), turning the one place that state would show
     * itself into the place that hides it. The diagram is a faithful projection of the strip; if it ever
     * draws a chat twice, the strip is what is wrong.
     */
    fun workloadsJson(workloads: List<JcefSessionData.Workload>, windowMinutes: Int, nowMillis: Long) =
        buildJsonArray {
            workloads.forEach { w ->
                val shown = visible(w.session, windowMinutes, nowMillis)
                addJsonObject {
                    put("chatId", w.chatId)
                    put("title", w.title)
                    put("selected", w.selected)
                    put("tree", agentTreeJson(w.session, shown))
                    put("tasks", backgroundTasksJson(w.session, shown))
                }
            }
        }

    fun agentTreeJson(session: ClaudeSession, shown: WorkloadWindow.Visible) = buildJsonArray {
        val nodes = session.runningAgents.nodes
        // The breadcrumb is read off the FULL map while the rows are the windowed ones: the chain is where an
        // agent came from, which the window does not change, and the map is the only thing that knows it.
        nodes.values.filter { it.agentId in shown.agents }.forEach { node ->
            addJsonObject {
                put("agentId", node.agentId)
                put("label", node.meta.label())
                put("type", node.meta.agentType)
                put("status", JcefStatus.of(node.status))
                put("depth", node.depth)
                put("parent", node.parentAgentId)
                put("chain", ownershipChain(session.title, node.agentId, nodes))
                put("running", node.status == AgentStatus.RUNNING)
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
    fun backgroundTasksJson(session: ClaudeSession, shown: WorkloadWindow.Visible) = buildJsonArray {
        val nodes = session.runningAgents.nodes
        // The plugin's own record rather than the binary's live set, and for one reason: that set is a LEVEL
        // signal, so a task that finished simply stops being listed — its row vanished from this window the
        // instant it ended, taking its output with it. The registry keeps finished tasks, marked as such, and
        // already excludes agents (to the binary a running agent IS a background task, which is how this
        // window used to duplicate the Agents one).
        session.backgroundTaskRegistry.all.filter { it.taskId in shown.tasks }.forEach { task ->
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
