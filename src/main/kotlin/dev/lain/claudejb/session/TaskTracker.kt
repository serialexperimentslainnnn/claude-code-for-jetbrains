package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.TaskNotificationInfo
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.TaskStartedInfo
import dev.lain.claudejb.protocol.TaskUpdatedInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous (no-IDE) state holder for subagent (Task tool) lifecycle events.
 *
 * Maintains an observable map keyed by `task_id`; latest progress wins. Extracted from
 * `ClaudeSession` so the lifecycle logic is testable in plain JVM. Callers are responsible for
 * threading (the session drives this on the EDT) and for firing UI state / emitting transcript
 * notices — this class only owns the data and the merge semantics.
 *
 * A `task_updated` patch ([onUpdated]) merges the changed lifecycle fields (status/description/error) into the
 * tracked entry, so a subagent flipping to paused/failed is reflected without waiting for a settling notification.
 */
class TaskTracker {
    private val backing = ConcurrentHashMap<String, TaskProgressInfo>()

    /** Immutable snapshot of the live tasks, keyed by `task_id`. */
    val tasks: Map<String, TaskProgressInfo> get() = backing.toMap()

    /**
     * A subagent task began. Honors `skip_transcript`: ambient/housekeeping tasks are not tracked.
     * Returns true when the task was added (i.e. callers should fire state).
     */
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

    /** Periodic progress for a running subagent. Latest progress replaces the tracked entry. */
    fun onProgress(info: TaskProgressInfo) {
        backing[info.taskId] = info
    }

    /**
     * Merge a wire-safe patch into the tracked task (only when the task is known). Each patch field overrides the
     * tracked value when present, leaving the rest intact — so a `task_updated` carrying just `status:"paused"` or
     * an `error` is reflected in the UI immediately.
     */
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

    /**
     * A subagent settled (completed/failed/stopped): drop it from the live map.
     * Returns whether the caller should surface a transcript notice (i.e. `!skip_transcript`).
     */
    fun onNotification(info: TaskNotificationInfo): Boolean {
        backing.remove(info.taskId)
        return !info.skipTranscript
    }

    /** Drop all tracked tasks. */
    fun clear() {
        backing.clear()
    }
}
