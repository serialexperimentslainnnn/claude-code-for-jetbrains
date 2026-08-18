package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.session.BackgroundTaskRegistry
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The tab bar's payload: the chats, this chat's agent tree, and its background tasks.
 *
 * **The bar is drawn by the web app, not by Swing.** The chat UI has been a JCEF page since 4.0.0 and this
 * belongs to it: a Swing strip cannot share the page's type scale, its accent, its transitions or its SVG,
 * so every attempt to make the two look like one product ends up approximating the other by hand — which is
 * exactly what it looked like.
 *
 * **Every chat's page draws the whole chat list.** There is one browser per chat, so no single page owns the
 * bar; each renders the same list and marks its own entry. Switching chats swaps browsers, and because both
 * pages paint the same bar the swap is invisible.
 *
 * **Only the SELECTED chat's work travels.** The bar's second row is the open chat's agents, subagents and
 * background tasks; nothing on screen asks what another chat started. Each chat used to carry its own `tree`
 * and `tasks` as well, so that hovering a tab you were not in could open that chat's subtree — the panel that
 * did was removed with the `⋮`, and what was left was a full serialisation of every open session's agent tree
 * on every agent event, several times a turn, for fields the page no longer reads. The whole picture is the
 * dashboard's Workloads diagram ([JcefSessionData]), which asks for it when it is on screen.
 *
 * The tree is sent FLAT — `{id, parent, label, status, type}` — and the levels are derived in the page from
 * whichever agent is selected. That is deliberate: which levels are open is a view state that changes on
 * every click, and round-tripping it through the host would make a click cost a repaint of the host's own
 * model. The host owns what EXISTS; the page owns what is SHOWN.
 */
object JcefTabsData {

    /** One chat in the bar. [id] is the strip's own handle, opaque to the page. */
    data class Chat(
        val id: String,
        val title: String,
        val selected: Boolean,
        val attention: Boolean = false,
    )

    /**
     * [windowMinutes] and [nowMillis] are the retention window and the instant to measure it from, resolved
     * once by the caller so that every push ages its work by the same instant rather than by however long the
     * serialisation took.
     */
    fun tabsJson(
        session: ClaudeSession,
        chats: List<Chat>,
        hiddenAgents: Set<String>,
        windowMinutes: Int,
        nowMillis: Long,
    ): String =
        buildTabs(session, chats, hiddenAgents, JcefWorkloadData.visible(session, windowMinutes, nowMillis))
            .toString()

    private fun buildTabs(
        session: ClaudeSession,
        chats: List<Chat>,
        hiddenAgents: Set<String>,
        shown: WorkloadWindow.Visible,
    ) = buildJsonObject {
        put(
            "chats",
            buildJsonArray {
                chats.forEach { chat ->
                    addJsonObject {
                        put("id", chat.id)
                        put("title", chat.title)
                        put("selected", chat.selected)
                        put("attention", chat.attention)
                    }
                }
            },
        )
        // The selected chat's tree, at the top level: it is what the bar's own rows are built from.
        put("tree", treeJson(session, hiddenAgents, shown))
        put("tasks", tasksJson(session, shown))
    }

    private fun treeJson(
        session: ClaudeSession,
        hiddenAgents: Set<String>,
        shown: WorkloadWindow.Visible,
    ) = buildJsonArray {
        session.runningAgents.nodes.values
            .filterNot { it.agentId in hiddenAgents }
            .filter { it.agentId in shown.agents }
            .forEach { node ->
                addJsonObject {
                    put("id", node.agentId)
                    put("parent", node.parentAgentId)
                    put("label", node.meta.label())
                    put("type", node.meta.agentType)
                    put("status", JcefStatus.of(node.status))
                    put("running", node.status == AgentStatus.RUNNING)
                }
            }
    }

    /**
     * The background tasks, each with the agent that started it (null = this chat's own turns).
     *
     * From the plugin's own [BackgroundTaskRegistry] rather than the binary's live set: that set is a level
     * signal, so a finished task stops being listed and its pill would vanish at the moment its output is
     * worth reading.
     */
    private fun tasksJson(session: ClaudeSession, shown: WorkloadWindow.Visible) = buildJsonArray {
        session.backgroundTaskRegistry.all.filter { it.taskId in shown.tasks }.forEach { task ->
            addJsonObject {
                put("id", task.taskId)
                put("label", task.label())
                put("type", task.taskType)
                put("running", task.running)
                // ONE state vocabulary for everything the page colours (see [JcefStatus]): the JS used to
                // translate a boolean into `done` here and `completed` in the dashboard, so the same task
                // was two different colours depending on which view you read it in.
                put("status", JcefStatus.of(task.running))
                put("owner", session.ownerAgentOfTask(task.taskId))
            }
        }
    }
}
