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

internal object JcefWorkloadData {

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
        return parts.joinToString("  ›  ")
    }

    fun backgroundTasksJson(session: ClaudeSession, shown: WorkloadWindow.Visible) = buildJsonArray {
        val nodes = session.runningAgents.nodes
        session.backgroundTaskRegistry.all.filter { it.taskId in shown.tasks }.forEach { task ->
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
