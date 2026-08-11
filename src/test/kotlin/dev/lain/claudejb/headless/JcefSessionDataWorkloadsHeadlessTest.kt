package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefSessionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The Workloads diagram draws each chat ONCE.
 *
 * The bug this pins was reported as "a veces se duplican": pinning a subagent as a tab of its own adds a
 * second `ChatTab` over the SAME panel — a view of one agent's transcript, not another workload — so the
 * strip listed that session twice and the diagram drew the chat, its agents and its tasks twice over.
 */
class JcefSessionDataWorkloadsHeadlessTest : BasePlatformTestCase() {

    fun `test a session listed twice is drawn once`() {
        val session = ClaudeSession(project, "Chat")
        val workloads = listOf(
            JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = session),
            // What `pin()` produces: another tab, same session.
            JcefSessionData.Workload("tab-2", "Agent A", selected = false, session = session),
        )
        val drawn = Json.parseToJsonElement(JcefSessionData.sessionJson(session, workloads = workloads))
            .jsonObject["workloads"]!!.jsonArray
        assertEquals(1, drawn.size)
        // The chat's own tab wins, not the pinned view it spawned.
        assertEquals("tab-1", drawn.first().jsonObject["chatId"]!!.toString().trim('"'))
    }

    fun `test two real chats are both drawn`() {
        val workloads = listOf(
            JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = ClaudeSession(project, "Chat")),
            JcefSessionData.Workload("tab-2", "Chat 2", selected = false, session = ClaudeSession(project, "Chat")),
        )
        val drawn = Json.parseToJsonElement(JcefSessionData.sessionJson(ClaudeSession(project, "Chat"), workloads = workloads))
            .jsonObject["workloads"]!!.jsonArray
        assertEquals(2, drawn.size)
    }
}
