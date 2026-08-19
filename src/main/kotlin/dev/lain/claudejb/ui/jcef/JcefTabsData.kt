package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.session.BackgroundTaskRegistry
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefTabsData {

    data class Chat(
        val id: String,
        val title: String,
        val selected: Boolean,
        val attention: Boolean = false,
    )

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

    private fun tasksJson(session: ClaudeSession, shown: WorkloadWindow.Visible) = buildJsonArray {
        session.backgroundTaskRegistry.all.filter { it.taskId in shown.tasks }.forEach { task ->
            addJsonObject {
                put("id", task.taskId)
                put("label", task.label())
                put("type", task.taskType)
                put("running", task.running)
                put("status", JcefStatus.of(task.running))
                put("owner", session.ownerAgentOfTask(task.taskId))
            }
        }
    }
}
