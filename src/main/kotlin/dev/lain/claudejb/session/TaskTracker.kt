package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.protocol.TaskNotificationInfo
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.TaskStartedInfo
import dev.lain.claudejb.protocol.TaskUpdatedInfo
import java.util.concurrent.ConcurrentHashMap

class TaskTracker {
    private val backing = ConcurrentHashMap<String, TaskProgressInfo>()

    @Volatile
    private var backgroundBacking: List<BackgroundTaskInfo> = emptyList()

    val tasks: Map<String, TaskProgressInfo> get() = backing.toMap()

    val backgroundTasks: List<BackgroundTaskInfo> get() = backgroundBacking

    fun replaceBackgroundTasks(tasks: List<BackgroundTaskInfo>) {
        backgroundBacking = tasks
    }

    fun onStarted(info: TaskStartedInfo): Boolean {
        if (info.skipTranscript) return false
        backing[info.taskId] = TaskProgressInfo(
            taskId = info.taskId,
            toolUseId = info.toolUseId,
            description = info.description,
            subagentType = info.subagentType,
        )
        return true
    }

    fun onProgress(info: TaskProgressInfo) {
        backing[info.taskId] = info
    }

    fun onUpdated(info: TaskUpdatedInfo) {
        val patch = info.patch
        backing[info.taskId]?.let { cur ->
            backing[info.taskId] = cur.copy(
                description = patch.description ?: cur.description,
                status = patch.status ?: cur.status,
                error = patch.error ?: cur.error,
            )
        }
    }

    fun onNotification(info: TaskNotificationInfo): Boolean {
        backing.remove(info.taskId)
        return !info.skipTranscript
    }

    fun clear() {
        backing.clear()
        backgroundBacking = emptyList()
    }
}
