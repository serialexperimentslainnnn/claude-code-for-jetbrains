package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.TaskPatch
import dev.lain.claudejb.protocol.TaskStartedInfo
import dev.lain.claudejb.protocol.TaskUpdatedInfo
import dev.lain.claudejb.session.AgentMeta
import dev.lain.claudejb.session.AgentStatus
import dev.lain.claudejb.session.ClaudeSession
import java.nio.file.Files
import java.nio.file.Path

/**
 * **A `system/task_updated` ends the agent it is about, even though it does not name it.**
 *
 * The message carries a `task_id` and a patch — [TaskUpdatedInfo] has no `tool_use_id` field at all, because
 * the wire shape has none — while everything downstream of it is keyed by the Task call's `tool_use_id`
 * (`AgentRegistry.observeSettled`). The only bridge between the two is what an earlier `task_started`
 * recorded in `TaskTracker`, and `ClaudeSession` is where that lookup happens. Nothing else in the build
 * checks that it does: `TaskTrackerTest` proves the tracker merges the patch, `AgentRegistryTest` proves the
 * registry settles a `tool_use_id` it is given, and between those two green suites sat the defect — the
 * patch reached the task map and stopped there.
 *
 * **What that cost, and why it is a headless test rather than a unit one.** `killed` only ever arrives this
 * way. With the lookup missing, the agent went on reporting RUNNING for the rest of the session, and with it
 * the Task card standing for it in the transcript (`labelAgentCards` hands that card the agent's status) and
 * its row in the Workloads diagram (`WorkloadWindow.isVisible` exempts running work by design, so the row
 * never expired). One cause, three symptoms, none of them an error anywhere — which is exactly the shape of
 * defect that needs the real wiring exercised end to end instead of a collaborator in isolation.
 *
 * The path under test is therefore the whole of it: the `ClaudeSession.handleEventForTest` seam → the private
 * `settleFromLifecycle` → `AgentRegistry`, read back through the registry's own snapshot. The agents come
 * from real sidecar files laid out the way the binary lays them out, under a redirected `user.home` — the
 * same fixture approach `ResumeFlowIntegrationTest` uses, for the same reason: `SessionStore` reads the real
 * home otherwise, and a test JVM has no business there.
 *
 * **Why the assertions can be trusted to be measuring something.** Every case here is a PAIR: the same scan,
 * the same snapshot and the same assertion produce an ending for one agent and RUNNING for another, or an
 * ending for one status word and none for a `task_id` nobody started. A gate whose verdict has only ever
 * been seen green cannot be told apart from one that reports nothing, and a status read that answered
 * `STOPPED` unconditionally would satisfy a single-sided test.
 *
 * WHAT THIS DOES NOT COVER, stated rather than left to be discovered:
 *  - The **scan is driven by hand** ([settleAndScan] calls `AgentRegistry.scan` directly). Production reaches
 *    it through `AgentScanner`, on a pooled thread; that hop is deliberately not under test here, because
 *    asserting on it would mean waiting on a thread and this suite does not do flaky.
 *  - The three SYMPTOMS above (the Task card's colour, the Workloads row, the tab's badge) are downstream of
 *    the status asserted here and are not re-asserted from it.
 *  - `task_progress` carries the same `status` vocabulary through the same `settleFromLifecycle`, but names
 *    its own `tool_use_id`, so it never needed the lookup and is not exercised.
 */
class AgentLifecycleSettleHeadlessTest : BasePlatformTestCase() {

    /** A binary-issued session id shape: `SessionStore` refuses anything else, as a path-traversal guard. */
    private val sessionId = "abcdabcd-1111-2222-3333-444455556666"

    private var savedHome: String? = null
    private var tmpHome: Path? = null
    private lateinit var subagents: Path

    override fun setUp() {
        super.setUp()
        val home = Files.createTempDirectory("agent-settle-home")
        tmpHome = home
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        // The binary's own layout: `<sessionId>.jsonl` with a directory of the same name beside it. The
        // project folder name is the cwd encoding, and `SessionStore.locate` finds the transcript by id
        // whichever folder it is in — so any name does, exactly as in `ResumeFlowIntegrationTest`.
        val projectDir = home.resolve(".claude").resolve("projects").resolve("-tmp-project")
        subagents = projectDir.resolve(sessionId).resolve("subagents")
        Files.createDirectories(subagents)
        Files.writeString(projectDir.resolve("$sessionId.jsonl"), "")
    }

    override fun tearDown() {
        try {
            savedHome?.let { System.setProperty("user.home", it) }
            tmpHome?.let { runCatching { it.toFile().deleteRecursively() } }
        } finally {
            super.tearDown()
        }
    }

    fun `test a killed patch with no tool_use_id ends the agent its task started`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t1", status = "killed")

        // `killed` is an ending — the whole reason the lookup exists, since no other message carries it.
        assertEquals(AgentStatus.STOPPED, agents.getValue("a1"))
        // …and it ended the RIGHT one. This is also what makes the line above a measurement: the same scan,
        // read the same way, answers differently for the agent the patch was not about.
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a status word this build does not know fails the agent rather than leaving it running`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t1", status = "spontaneously-combusted")

        // The fallback is FAILED on purpose: an unknown word means the binary has stopped saying something we
        // understand about a piece of work, and a red row is wrong LOUDLY while a permanently spinning one is
        // wrong silently — it never settles, never leaves the Workloads window and never colours its card.
        assertEquals(AgentStatus.FAILED, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a live status word is not an ending`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t1", status = "paused")

        // Paused work has not finished and nothing about it failed. Forwarding every live tick would also
        // UNDO an ending already observed, which is why only endings are passed on at all.
        assertEquals(AgentStatus.RUNNING, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a patch for a task nobody started settles nothing`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        // The resolution is the subject: with no tracked task there is no `tool_use_id` to key on, and the
        // ending has nowhere to go. It must be dropped rather than applied to whatever is at hand.
        val agents = settleAndScan(taskId = "t-never-started", status = "killed")

        assertEquals(AgentStatus.RUNNING, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    /**
     * Starts two Task calls, applies one `task_updated` patch, then reads every agent's status.
     *
     * The session id is set AFTER the events and not through a `system/init`, and both halves are
     * deliberate. The scans those events trigger run on a pooled thread and resolve the subagents directory
     * through `sessionId`, so while it is null they return before touching the snapshot — which means the
     * only scan that can write one is the synchronous call below, made after the settle is already recorded.
     * That is what makes this test deterministic instead of a race with a background thread. Feeding an
     * `Init` instead would additionally mark the registry as RESTORING, i.e. a different admission rule and a
     * different reading of an unfinished transcript, which is not what is under test here.
     */
    private fun settleAndScan(taskId: String, status: String): Map<String, AgentStatus> {
        val session = ClaudeSession(project, "t")
        try {
            started(session, taskId = "t1", toolUse = "tu-1")
            started(session, taskId = "t2", toolUse = "tu-2")
            session.handleEventForTest(
                ClaudeEvent.TaskUpdated(TaskUpdatedInfo(taskId = taskId, patch = TaskPatch(status = status))),
            )
            flush()
            session.sessionId = sessionId
            session.runningAgents.scan()
            return session.runningAgents.nodes.mapValues { (_, node) -> node.status }
        } finally {
            session.dispose()
            flush()
        }
    }

    /** A Task call the plugin watched start: the admission seed, and the only record of its `tool_use_id`. */
    private fun started(session: ClaudeSession, taskId: String, toolUse: String) {
        session.handleEventForTest(
            ClaudeEvent.TaskStarted(
                TaskStartedInfo(taskId = taskId, toolUseId = toolUse, description = "Task $taskId"),
            ),
        )
        flush()
    }

    /**
     * One agent's sidecar, written the way the binary writes it — the FILE carries the `agent-` prefix while
     * the identity inside does not (see [AgentMeta]).
     *
     * No transcript file is written, on purpose: an agent with nothing to read is the state an agent is in
     * for most of its life, and it is the state in which the ONLY thing that can settle it is the lifecycle
     * signal this test drives.
     */
    private fun agentFile(agentId: String, toolUse: String) {
        Files.writeString(
            subagents.resolve("${AgentMeta.FILE_PREFIX}$agentId${AgentMeta.META_SUFFIX}"),
            """{"agentType":"general-purpose","description":"Task $agentId","toolUseId":"$toolUse","spawnDepth":1}""",
        )
    }

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
}
