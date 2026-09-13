package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.ToolState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SubagentToolCardsHeadlessTest : BasePlatformTestCase() {

    fun `test a subagent's tool calls draw cards nested under its Task card, own calls included`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(ClaudeEvent.ToolUse(TASK, "Task", buildJsonObject { put("description", "explore") }, null))
            session.handleEventForTest(ClaudeEvent.ToolUse("tu-read", "Read", buildJsonObject { put("file_path", "/x/A.kt") }, TASK))
            session.handleEventForTest(ClaudeEvent.ToolUse("tu-own", "mcp__code__run", ownReadFile(), TASK))
            session.handleEventForTest(ClaudeEvent.ToolResult("tu-read", "package x", isError = false, parentToolUseId = TASK))
            flush()

            val cards = session.transcript.entries.filter { it.speaker == Speaker.TOOL }
            assertEquals(listOf(null, TASK, TASK), cards.map { it.parentToolUseId })
            assertEquals("code ▸ read_file ▸ src/A.kt", cards[2].text)
            assertEquals(ToolState.FINISHED, cards[1].toolState)
            val output = session.transcript.entries.single { it.speaker == Speaker.TOOL_OUTPUT && it.toolUseId == "tu-read" }
            assertEquals(TASK, output.parentToolUseId)
            assertEquals("package x", output.text)
        } finally {
            session.dispose()
        }
    }

    fun `test a subagent's text still stays out of the main transcript`() {
        val session = ClaudeSession(project, "t")
        try {
            session.handleEventForTest(ClaudeEvent.ToolUse(TASK, "Task", buildJsonObject { put("description", "explore") }, null))
            session.handleEventForTest(ClaudeEvent.AssistantText("the agent talking", TASK))
            flush()
            assertTrue(session.transcript.entries.none { it.speaker == Speaker.ASSISTANT })
        } finally {
            session.dispose()
        }
    }

    private fun ownReadFile() = buildJsonObject {
        put("tool", "read_file")
        put("args", buildJsonObject { put("path", "src/A.kt") })
    }

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    private companion object {
        const val TASK = "task-1"
    }
}
