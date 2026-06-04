package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.TaskNotificationInfo
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.TaskStartedInfo
import dev.lain.claudejb.protocol.TaskUpdatedInfo
import dev.lain.claudejb.protocol.TaskPatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the subagent (Task tool) lifecycle merge semantics extracted from `ClaudeSession.onEvent`:
 * started adds (honoring skip_transcript), progress replaces, updated merges only `description`
 * (E10 will widen the DTO), notification drops and reports whether to notify the transcript.
 */
class TaskTrackerTest {

    @Test
    fun `started adds a task and reports it`() {
        val t = TaskTracker()
        val added = t.onStarted(
            TaskStartedInfo(
                taskId = "t1",
                toolUseId = "tu1",
                description = "find bugs",
                subagentType = "general",
            )
        )
        assertTrue(added)
        val task = t.tasks["t1"]!!
        assertEquals("t1", task.taskId)
        assertEquals("tu1", task.toolUseId)
        assertEquals("find bugs", task.description)
        assertEquals("general", task.subagentType)
        assertEquals(1, t.tasks.size)
    }

    @Test
    fun `started with skipTranscript does not track and returns false`() {
        val t = TaskTracker()
        val added = t.onStarted(TaskStartedInfo(taskId = "t1", description = "ambient", skipTranscript = true))
        assertFalse(added)
        assertTrue(t.tasks.isEmpty())
    }

    @Test
    fun `progress replaces the tracked entry, latest wins`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "initial"))
        t.onProgress(
            TaskProgressInfo(
                taskId = "t1",
                description = "halfway",
                lastToolName = "Grep",
                summary = "scanning",
            )
        )
        val task = t.tasks["t1"]!!
        assertEquals("halfway", task.description)
        assertEquals("Grep", task.lastToolName)
        assertEquals("scanning", task.summary)
    }

    @Test
    fun `updated applies the description patch`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "old"))
        t.onUpdated(TaskUpdatedInfo(taskId = "t1", patch = TaskPatch(description = "new", status = "running")))
        assertEquals("new", t.tasks["t1"]!!.description)
    }

    @Test
    fun `updated merges status and error into the tracked task`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "do it"))
        t.onUpdated(TaskUpdatedInfo(taskId = "t1", patch = TaskPatch(status = "failed", error = "boom")))
        val task = t.tasks["t1"]!!
        assertEquals("failed", task.status)
        assertEquals("boom", task.error)
        assertEquals("do it", task.description) // untouched fields preserved
    }

    @Test
    fun `updated with null status keeps the current status`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "x"))
        t.onUpdated(TaskUpdatedInfo(taskId = "t1", patch = TaskPatch(status = "paused")))
        t.onUpdated(TaskUpdatedInfo(taskId = "t1", patch = TaskPatch(description = "y")))
        assertEquals("paused", t.tasks["t1"]!!.status)
    }

    @Test
    fun `updated with null description keeps current value`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "keep me"))
        t.onUpdated(TaskUpdatedInfo(taskId = "t1", patch = TaskPatch(status = "running")))
        assertEquals("keep me", t.tasks["t1"]!!.description)
    }

    @Test
    fun `updated for unknown task is a no-op`() {
        val t = TaskTracker()
        t.onUpdated(TaskUpdatedInfo(taskId = "ghost", patch = TaskPatch(description = "x")))
        assertNull(t.tasks["ghost"])
        assertTrue(t.tasks.isEmpty())
    }

    @Test
    fun `notification removes the task and reports notify when not skipped`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "work"))
        val notify = t.onNotification(TaskNotificationInfo(taskId = "t1", status = "completed", skipTranscript = false))
        assertTrue(notify)
        assertNull(t.tasks["t1"])
        assertTrue(t.tasks.isEmpty())
    }

    @Test
    fun `notification with skipTranscript removes but reports no notify`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "work"))
        val notify = t.onNotification(TaskNotificationInfo(taskId = "t1", status = "stopped", skipTranscript = true))
        assertFalse(notify)
        assertNull(t.tasks["t1"])
    }

    @Test
    fun `tasks is an immutable snapshot decoupled from later mutations`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "a"))
        val snapshot = t.tasks
        t.onStarted(TaskStartedInfo(taskId = "t2", description = "b"))
        assertEquals(1, snapshot.size)
        assertEquals(2, t.tasks.size)
    }

    @Test
    fun `clear drops all tracked tasks`() {
        val t = TaskTracker()
        t.onStarted(TaskStartedInfo(taskId = "t1", description = "a"))
        t.onStarted(TaskStartedInfo(taskId = "t2", description = "b"))
        t.clear()
        assertTrue(t.tasks.isEmpty())
    }
}
