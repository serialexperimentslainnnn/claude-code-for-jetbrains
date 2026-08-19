package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundTaskRegistryTest {

    private var clock = 1_000_000_000L

    private val runStarted = 900_000_000L

    private fun stamping() = BackgroundTaskRegistry(now = { clock }, runStartedAtMillis = runStarted)

    private fun level(vararg tasks: Pair<String, String>) =
        tasks.map { (id, type) -> BackgroundTaskInfo(taskId = id, taskType = type, description = "desc $id") }

    private fun result(
        toolUseId: String,
        taskId: String,
        parent: String? = null,
        stdout: String? = null,
        outputFile: String? = null,
    ) = ClaudeEvent.ToolResult(
        toolUseId = toolUseId,
        content = "",
        isError = false,
        parentToolUseId = parent,
        output = ClaudeEvent.ToolOutputInfo(backgroundTaskId = taskId, outputFile = outputFile, stdout = stdout),
    )

    @Test
    fun `a task that stops being listed is kept, marked finished`() {
        val reg = BackgroundTaskRegistry()
        assertTrue(reg.observe(result("toolu_1", "t1")))
        assertTrue(reg.observeLevel(level("t1" to "local_bash")))
        assertTrue(reg.taskOf("t1")!!.running)
        assertTrue(reg.observeLevel(emptyList()))
        assertEquals(1, reg.all.size)
        assertFalse(reg.taskOf("t1")!!.running)
    }

    @Test
    fun `an agent is not a background task`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("agent1" to BackgroundTaskRegistry.AGENT_TASK_TYPE, "t1" to "local_bash"))
        assertEquals(listOf("t1"), reg.all.map { it.taskId })
    }

    @Test
    fun `the structured tool output is what gives a task its owner, its card and its output`() {
        val reg = BackgroundTaskRegistry()
        reg.observeLevel(level("t1" to "local_bash"))
        assertNull(reg.taskOf("t1"))
        assertTrue(reg.observe(result("toolu_1", "t1", parent = "toolu_agent", stdout = "line one")))
        val task = reg.taskOf("t1")!!
        assertEquals("toolu_1", task.toolUseId)
        assertEquals("toolu_agent", task.ownerToolUseId)
        assertTrue(task.output.contains("line one"))
    }

    @Test
    fun `a later query of the output appends and never re-parents the task`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1", parent = "toolu_agent", stdout = "first"))
        reg.observe(result("toolu_2", "t1", parent = "toolu_other", stdout = "second"))
        val task = reg.taskOf("t1")!!
        assertEquals("toolu_agent", task.ownerToolUseId)
        assertTrue(task.output.contains("first"))
        assertTrue(task.output.contains("second"))
    }

    @Test
    fun `a tool result with no background task id changes nothing`() {
        val reg = BackgroundTaskRegistry()
        assertFalse(reg.observe(ClaudeEvent.ToolResult("toolu_1", "ok", false, null, null)))
        assertTrue(reg.all.isEmpty())
    }

    @Test
    fun `an agent's output file never conjures a background task`() {
        val reg = BackgroundTaskRegistry()
        assertFalse(reg.observeOutputFile("a682c347feede06f3", "/tmp/whatever/agent.jsonl"))
        assertTrue(reg.all.isEmpty())
        reg.observe(result("toolu_1", "t1"))
        assertTrue(reg.observeOutputFile("t1", "/tmp/tasks/t1.output"))
        assertEquals("/tmp/tasks/t1.output", reg.taskOf("t1")!!.outputFile)
    }

    @Test
    fun `tailed output is appended to a known task only`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1", outputFile = "/tmp/agent.out"))
        assertTrue(reg.appendTailedOutput("t1", "progress\n"))
        assertTrue(reg.taskOf("t1")!!.output.contains("progress"))
        assertFalse(reg.appendTailedOutput("unknown", "x"))
        assertFalse(reg.appendTailedOutput("t1", "   "))
    }

    @Test
    fun `order is stable, so a row does not jump under the pointer`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        reg.observe(result("toolu_2", "t2"))
        reg.observeLevel(level("t2" to "local_bash"))
        reg.observe(result("toolu_3", "t3"))
        reg.observeLevel(level("t2" to "local_bash", "t3" to "local_bash"))
        assertEquals(listOf("t1", "t2", "t3"), reg.all.map { it.taskId })
    }

    @Test
    fun `clear drops everything, because the state is per-process`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        reg.clear()
        assertTrue(reg.all.isEmpty())
    }

    @Test
    fun `the level does not finish a task it never listed`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        assertTrue(reg.taskOf("t1")!!.running)

        assertFalse(reg.observeLevel(level("other" to "local_bash")), "nothing about t1 changed")
        assertTrue(reg.taskOf("t1")!!.running, "a task the level never listed must stay running")
    }

    @Test
    fun `once the level HAS listed a task, absence does end it`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("t1" to "local_bash"))
        assertTrue(reg.observeLevel(emptyList()))
        assertFalse(reg.taskOf("t1")!!.running, "the level owns the tasks it listed")
    }

    @Test
    fun `a terminal notification ends a task the level never listed`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        assertTrue(reg.settle("t1", "completed"))
        assertFalse(reg.taskOf("t1")!!.running)
    }

    @Test
    fun `only an ending ends it`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        listOf("running", "started", "progress", "", "weird_new_status").forEach {
            assertFalse(reg.settle("t1", it), "'$it' must not settle a task")
            assertTrue(reg.taskOf("t1")!!.running)
        }
        assertFalse(reg.settle("t1", null))
        assertTrue(reg.taskOf("t1")!!.running)
    }

    @Test
    fun `settling is idempotent and never invents a task`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        assertTrue(reg.settle("t1", "failed"))
        assertFalse(reg.settle("t1", "failed"), "already settled: nothing changed, so nothing to push")
        assertFalse(reg.settle("never-seen", "completed"))
        assertNull(reg.taskOf("never-seen"))
    }

    @Test
    fun `a task ended by its own notification is stamped at that instant`() {
        val reg = stamping()
        reg.observe(result("toolu_1", "t1"))
        assertNull(reg.taskOf("t1")!!.completedAtMillis, "a running task has not completed")

        clock = 1_000_000_000L
        reg.settle("t1", "completed")

        assertEquals(1_000_000_000L, reg.taskOf("t1")!!.completedAtMillis)
    }

    @Test
    fun `a task ended by dropping out of the level is stamped at that instant`() {
        val reg = stamping()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("t1" to "local_bash"))

        clock = 1_000_000_000L
        reg.observeLevel(emptyList())

        assertEquals(1_000_000_000L, reg.taskOf("t1")!!.completedAtMillis)
    }

    @Test
    fun `an instant already written is never moved`() {
        val reg = stamping()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("t1" to "local_bash"))
        clock = 1_000_000_000L
        reg.observeLevel(emptyList())

        clock = 1_000_600_000L
        reg.observeLevel(level("other" to "local_bash"))
        reg.settle("t1", "completed")

        assertEquals(1_000_000_000L, reg.taskOf("t1")!!.completedAtMillis)
    }

    @Test
    fun `a task rebuilt from a previous run is stamped when this run started`() {
        val reg = stamping()
        reg.seed(
            listOf(
                BackgroundTaskReplay.Replayed("t1", "toolu_1", null, "sleep 1", null, "out", ""),
                BackgroundTaskReplay.Replayed("t2", "toolu_2", null, "sleep 2", null, "out", ""),
            ),
        )

        assertEquals(900_000_000L, reg.taskOf("t1")!!.completedAtMillis)
        assertEquals(900_000_000L, reg.taskOf("t2")!!.completedAtMillis)
    }

    @Test
    fun `a task listed live again is not a completed one`() {
        val reg = stamping()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("t1" to "local_bash"))
        clock = 1_000_000_000L
        reg.observeLevel(emptyList())
        assertEquals(1_000_000_000L, reg.taskOf("t1")!!.completedAtMillis)

        clock = 1_000_600_000L
        reg.observeLevel(level("t1" to "local_bash"))
        assertNull(reg.taskOf("t1")!!.completedAtMillis)

        clock = 1_000_900_000L
        reg.observeLevel(emptyList())
        assertEquals(1_000_900_000L, reg.taskOf("t1")!!.completedAtMillis)
    }

    @Test
    fun `only a running task with a file to read keeps the poll running`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        assertFalse(reg.anyTailable)

        reg.observe(result("toolu_1", "t1", outputFile = "/tmp/t1.output"))
        assertTrue(reg.anyTailable)

        reg.settle("t1", "completed")
        assertFalse(reg.anyTailable)
    }
}
