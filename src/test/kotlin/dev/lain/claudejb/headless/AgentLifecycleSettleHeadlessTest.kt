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

class AgentLifecycleSettleHeadlessTest : BasePlatformTestCase() {

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

        assertEquals(AgentStatus.STOPPED, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a status word this build does not know fails the agent rather than leaving it running`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t1", status = "spontaneously-combusted")

        assertEquals(AgentStatus.FAILED, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a live status word is not an ending`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t1", status = "paused")

        assertEquals(AgentStatus.RUNNING, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

    fun `test a patch for a task nobody started settles nothing`() {
        agentFile("a1", toolUse = "tu-1")
        agentFile("a2", toolUse = "tu-2")

        val agents = settleAndScan(taskId = "t-never-started", status = "killed")

        assertEquals(AgentStatus.RUNNING, agents.getValue("a1"))
        assertEquals(AgentStatus.RUNNING, agents.getValue("a2"))
    }

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

    private fun started(session: ClaudeSession, taskId: String, toolUse: String) {
        session.handleEventForTest(
            ClaudeEvent.TaskStarted(
                TaskStartedInfo(taskId = taskId, toolUseId = toolUse, description = "Task $taskId"),
            ),
        )
        flush()
    }

    private fun agentFile(agentId: String, toolUse: String) {
        Files.writeString(
            subagents.resolve("${AgentMeta.FILE_PREFIX}$agentId${AgentMeta.META_SUFFIX}"),
            """{"agentType":"general-purpose","description":"Task $agentId","toolUseId":"$toolUse","spawnDepth":1}""",
        )
    }

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
}
