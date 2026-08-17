package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BackgroundTaskRegistry] — the record that survives the level signal.
 *
 * Two user-reported failures are pinned here. A finished task **vanished** from the rows, the tabs and the
 * dashboard the moment it ended, because `background_tasks_changed` lists only what is live. And a task's tab
 * led nowhere, because the level signal carries no owner and no tool call — that link only exists in the
 * structured tool output.
 */
class BackgroundTaskRegistryTest {

    /**
     * The registry's clock, moved by hand. Injected rather than read inside the registry so a completion
     * instant can be asserted as a literal — the alternative is recomputing it here with the subject's own
     * expression, which asserts nothing.
     */
    private var clock = 1_000_000_000L

    /** When this run of the plugin started, as the registry is told it: the stamp a replayed task gets. */
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
        // The tool_result is what makes a task OURS; the level only reports liveness.
        assertTrue(reg.observe(result("toolu_1", "t1")))
        assertTrue(reg.observeLevel(level("t1" to "local_bash")))
        assertTrue(reg.taskOf("t1")!!.running)
        // THE BUG: absence from the level means "finished", not "never existed". Dropping it here is what
        // made the row, the tab and the output disappear at the exact moment there was something to read.
        assertTrue(reg.observeLevel(emptyList()))
        assertEquals(1, reg.all.size)
        assertFalse(reg.taskOf("t1")!!.running)
    }

    @Test
    fun `an agent is not a background task`() {
        val reg = BackgroundTaskRegistry()
        // To the binary a running agent IS a background task (`local_agent`). Kept here, every agent showed
        // up a second time in the Background tasks row — and, resolving its owner through its own Task call,
        // as a background task OF ITSELF.
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("agent1" to BackgroundTaskRegistry.AGENT_TASK_TYPE, "t1" to "local_bash"))
        assertEquals(listOf("t1"), reg.all.map { it.taskId })
    }

    @Test
    fun `the structured tool output is what gives a task its owner, its card and its output`() {
        val reg = BackgroundTaskRegistry()
        // The level alone creates NOTHING: its ids do not always match `backgroundTaskId`, and adopting the
        // strangers produced a second, contentless copy of every task.
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
        // A later look at the same task can come from a different turn, or a different agent. Letting that
        // overwrite the owner would re-parent the task to whoever last looked at it.
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
        // `task_notification` fires for AGENTS too, and creating from it registered every agent as a
        // background task: a row per agent with no description, whose "output" was the agent's own
        // transcript — pages of raw JSONL where a command's output belongs. Reported live, and the id gave
        // it away: the task id in the view was the agent id.
        assertFalse(reg.observeOutputFile("a682c347feede06f3", "/tmp/whatever/agent.jsonl"))
        assertTrue(reg.all.isEmpty())
        // Once the tool_result has made the task ours, the same call is what gives it its file.
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
        reg.observeLevel(level("t2" to "local_bash")) // t1 finished
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

    // ── The level may only settle what it once claimed ────────────────────────────────────────────────────

    @Test
    fun `the level does not finish a task it never listed`() {
        val reg = BackgroundTaskRegistry()
        // A backgrounded shell command exists because its tool_result said so. The level signal has never
        // mentioned it — and may never mention it at all.
        reg.observe(result("toolu_1", "t1"))
        assertTrue(reg.taskOf("t1")!!.running)

        // THE BUG the user saw as "green while it is plainly still writing output": the next level fires for
        // some OTHER task, ours is absent, and absence was read as an ending. It is not: this signal never
        // claimed our task, so it has nothing to say about its ending.
        assertFalse(reg.observeLevel(level("other" to "local_bash")), "nothing about t1 changed")
        assertTrue(reg.taskOf("t1")!!.running, "a task the level never listed must stay running")
    }

    @Test
    fun `once the level HAS listed a task, absence does end it`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        reg.observeLevel(level("t1" to "local_bash")) // claimed
        assertTrue(reg.observeLevel(emptyList()))
        assertFalse(reg.taskOf("t1")!!.running, "the level owns the tasks it listed")
    }

    // ── `task_notification` is the authoritative ending for the rest ──────────────────────────────────────

    @Test
    fun `a terminal notification ends a task the level never listed`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        // Without this the row would stay running for ever, since only the level settles what it lists.
        assertTrue(reg.settle("t1", "completed"))
        assertFalse(reg.taskOf("t1")!!.running)
    }

    @Test
    fun `only an ending ends it`() {
        val reg = BackgroundTaskRegistry()
        reg.observe(result("toolu_1", "t1"))
        // Progress is not an end, and neither is a status this build has never heard of. Guessing here is how
        // a running task goes green mid-flight — exactly the failure this pair of rules exists to stop.
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
        // `task_notification` fires for AGENTS too, which have their own tabs. It may never create here.
        assertFalse(reg.settle("never-seen", "completed"))
        assertNull(reg.taskOf("never-seen"))
    }

    // ── When a task stopped, which is what the retention window measures ──────────────────────────────────

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
        reg.observeLevel(level("t1" to "local_bash")) // claimed, so absence will end it

        clock = 1_000_000_000L
        reg.observeLevel(emptyList())

        assertEquals(1_000_000_000L, reg.taskOf("t1")!!.completedAtMillis)
    }

    @Test
    fun `an instant already written is never moved`() {
        // The level signal re-sends its whole set on every membership change, several times a turn. A task
        // that ended ten minutes ago must not be dated by whichever push happens to arrive next.
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
        // Nobody here watched it end — the transcript did. Leaving it unstamped would put it outside the
        // retention window for good; stamping it at admission means it is there when the IDE reopens and
        // ages out like everything else.
        val reg = stamping()
        reg.seed(
            listOf(
                BackgroundTaskReplay.Replayed("t1", "toolu_1", null, "sleep 1", null, "out", ""),
                BackgroundTaskReplay.Replayed("t2", "toolu_2", null, "sleep 2", null, "out", ""),
            ),
        )

        // One instant for the whole run: two tasks that came back together vanish together.
        assertEquals(900_000_000L, reg.taskOf("t1")!!.completedAtMillis)
        assertEquals(900_000_000L, reg.taskOf("t2")!!.completedAtMillis)
    }

    @Test
    fun `a task listed live again is not a completed one`() {
        // The level says it is running, so it has not completed. Keeping the old instant would date its next
        // ending to the previous one, and the row would disappear while the task was still writing output.
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

    // ── What keeps the live-output poll alive ─────────────────────────────────────────────────────────────

    @Test
    fun `only a running task with a file to read keeps the poll running`() {
        val reg = BackgroundTaskRegistry()
        // No output file yet: nothing to tail, so the timer must not start.
        reg.observe(result("toolu_1", "t1"))
        assertFalse(reg.anyTailable)

        // The launching result NAMES the file. That is the moment the poll has to begin, because a
        // backgrounded command emits nothing else until it finishes.
        reg.observe(result("toolu_1", "t1", outputFile = "/tmp/t1.output"))
        assertTrue(reg.anyTailable)

        // …and it must stop on its own when the task ends, or an idle session polls for ever.
        reg.settle("t1", "completed")
        assertFalse(reg.anyTailable)
    }
}
