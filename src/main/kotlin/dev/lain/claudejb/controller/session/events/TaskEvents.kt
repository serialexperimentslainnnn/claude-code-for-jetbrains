package dev.lain.claudejb.controller.session.events

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.agents.AgentStatus
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.SubagentNotice
import dev.lain.claudejb.model.session.transcript.ToolState

class TaskEvents(
    private val s: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
) {

    fun onTask(event: ClaudeEvent.Task) {
        when (event) {
            is ClaudeEvent.TaskStarted -> edt {
                s.runningAgents.observeSpawn(event.info.toolUseId)
                s.agentScanner.scan()
                if (s.taskTracker.onStarted(event.info)) fireState()
            }

            is ClaudeEvent.TaskProgress -> edt {
                s.runningAgents.observeSpawn(event.info.toolUseId)
                settleFromLifecycle(event.info.toolUseId, event.info.status)
                s.agentScanner.scan()
                s.taskTracker.onProgress(event.info)
                fireState()
            }

            is ClaudeEvent.TaskUpdated -> edt {
                s.taskTracker.onUpdated(event.info)
                val ended = settleFromLifecycle(s.taskTracker.tasks[event.info.taskId]?.toolUseId, event.info.patch.status)
                if (ended) s.agentScanner.scan()
                fireState()
            }

            is ClaudeEvent.TaskNotification -> edt {
                s.runningAgents.observeSettled(event.info.toolUseId, AgentStatus.parse(event.info.status))
                s.backgroundTaskRegistry.observeOutputFile(event.info.taskId, event.info.outputFile)
                s.backgroundTaskRegistry.settle(event.info.taskId, event.info.status)
                s.agentScanner.tailNow()
                s.agentScanner.scan()
                if (s.taskTracker.onNotification(event.info)) {
                    val head = SubagentNotice.headline(event.info.summary)
                    s.systemNotice("Subagent ${event.info.status}" + (head?.let { ": $it" } ?: ""))
                }
                fireState()
            }

            is ClaudeEvent.ToolProgress -> edt {
                s.transcript.setToolState(event.info.toolUseId, ToolState.RUNNING, event.info.elapsedTimeSeconds)
            }

            is ClaudeEvent.ToolUseSummary -> edt {
                if (event.info.summary.isNotBlank()) s.transcript.add(Speaker.SYSTEM, "↳ ${event.info.summary}")
            }

            is ClaudeEvent.BackgroundTasksChanged -> edt {
                s.taskTracker.replaceBackgroundTasks(event.info.tasks)
                s.backgroundTaskRegistry.observeLevel(event.info.tasks)
                s.poll.ensureOutputTail()
                fireState()
            }
        }
    }

    private fun settleFromLifecycle(toolUseId: String?, status: String?): Boolean {
        if (toolUseId.isNullOrBlank() || status.isNullOrBlank()) return false
        val ending = AgentStatus.parse(status).takeIf { it != AgentStatus.RUNNING } ?: return false
        s.runningAgents.observeSettled(toolUseId, ending)
        return true
    }
}
