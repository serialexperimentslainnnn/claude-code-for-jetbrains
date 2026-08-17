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
 * The tree is sent FLAT — `{agentId, parent, label, status}` — and the levels are derived in the page from
 * whichever agent is selected. That is deliberate: which levels are open is a view state that changes on
 * every click, and round-tripping it through the host would make a click cost a repaint of the host's own
 * model. The host owns what EXISTS; the page owns what is SHOWN.
 */
object JcefTabsData {

    /**
     * One chat in the bar. [id] is the strip's own handle, opaque to the page.
     *
     * [pinnedAgent] is set on a tab that was pinned to a subagent: the tab shows that agent's transcript, so
     * its ⋮ must open THAT agent's subtree, not the whole chat's. Without it the page has no way to tell a
     * pinned tab from an ordinary chat and shows the global tree — which is what a pinned tab is not about.
     */
    data class Chat(
        val id: String,
        val title: String,
        val selected: Boolean,
        val attention: Boolean = false,
        val pinnedAgent: String? = null,
    )

    /**
     * [windowMinutes] and [nowMillis] are the retention window and the instant to measure it from, resolved
     * once by the caller so that every chat in one push ages by the same instant.
     */
    fun tabsJson(
        session: ClaudeSession,
        chats: List<Chat>,
        hiddenAgents: Set<String>,
        windowMinutes: Int,
        nowMillis: Long,
        others: Map<String, ClaudeSession> = emptyMap(),
    ): String = buildTabs(session, chats, WorkloadView(hiddenAgents, windowMinutes, nowMillis), others).toString()

    /**
     * Which workloads are visible at one instant: the retention window, the instant it is measured from, and
     * the agents the user dismissed in this panel's own chat.
     *
     * One value rather than three parameters threaded side by side, because they are one fact. Every chat in a
     * push is judged by the same window and the same instant, and [visible] is the single place that says so —
     * the pair was previously restated at each of the two call sites that needed it.
     */
    private data class WorkloadView(
        val hiddenAgents: Set<String>,
        val windowMinutes: Int,
        val nowMillis: Long,
    ) {
        fun visible(session: ClaudeSession) = JcefWorkloadData.visible(session, windowMinutes, nowMillis)
    }

    private fun buildTabs(
        session: ClaudeSession,
        chats: List<Chat>,
        view: WorkloadView,
        others: Map<String, ClaudeSession>,
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
                        // A tab pinned to a subagent roots its own ⋮ at that agent, not at the chat.
                        chat.pinnedAgent?.let { put("pinned", it) }
                        // EVERY chat carries its own tree, not just the selected one: hovering a tab you are
                        // not in has to show what THAT chat started. Its work does not pause because you are
                        // reading a different tab, and having to select a chat to find out what it is doing
                        // is the opposite of what a tab bar is for.
                        //
                        // `hiddenAgents` is deliberately NOT applied here — it is this panel's own record of
                        // what the user dismissed in ITS chat, and it says nothing about anyone else's.
                        others[chat.id]?.let { s ->
                            val shown = view.visible(s)
                            put("tree", treeJson(s, if (s === session) view.hiddenAgents else emptySet(), shown))
                            put("tasks", tasksJson(s, shown))
                        }
                    }
                }
            },
        )
        // The selected chat's tree, kept at the top level: it is what the bar's own rows are built from.
        val shown = view.visible(session)
        put("tree", treeJson(session, view.hiddenAgents, shown))
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
     * From the plugin's own registry rather than the binary's live set: that set is a level signal, so a
     * finished task stops being listed and its tab would vanish at the moment its output is worth reading.
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
