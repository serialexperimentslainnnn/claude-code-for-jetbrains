package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import dev.lain.claudejb.ui.jcef.JcefSessionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class JcefSessionDataWorkloadsHeadlessTest : BasePlatformTestCase() {

    fun `test every open chat is drawn, once each and in the strip's order`() {
        val workloads = listOf(
            JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = ClaudeSession(project, "Chat")),
            JcefSessionData.Workload("tab-2", "Chat 2", selected = false, session = ClaudeSession(project, "Chat")),
            JcefSessionData.Workload("tab-3", "Git", selected = false, session = ClaudeSession(project, "Chat")),
        )
        val drawn = drawnBy(workloads)

        assertEquals(3, drawn.size)
        assertEquals(listOf("tab-1", "tab-2", "tab-3"), drawn.map { it.jsonObject["chatId"]!!.text() })
        assertEquals(listOf("Chat 1", "Chat 2", "Git"), drawn.map { it.jsonObject["title"]!!.text() })
    }

    fun `test a chat with no work is still a node, rather than being dropped`() {
        val drawn = drawnBy(
            listOf(JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = ClaudeSession(project, "Chat"))),
        )

        assertEquals(1, drawn.size)
        assertTrue(drawn.first().jsonObject["tree"]!!.jsonArray.isEmpty())
        assertTrue(drawn.first().jsonObject["tasks"]!!.jsonArray.isEmpty())
    }

    fun `test no strip to ask means no workloads, not an invented one`() {
        assertTrue(drawnBy(emptyList()).isEmpty())
    }

    private fun drawnBy(workloads: List<JcefSessionData.Workload>) =
        Json.parseToJsonElement(
            JcefSessionData.sessionJson(
                ClaudeSession(project, "Chat"),
                windowMinutes = WorkloadWindow.ALL,
                nowMillis = NOW,
                workloads = workloads,
            ),
        ).jsonObject["workloads"]!!.jsonArray

    private fun JsonElement.text() = toString().trim('"')

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
