package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import dev.lain.claudejb.ui.jcef.JcefSessionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The Workloads diagram is a **faithful projection of the tab strip**: one node per open chat, in the strip's
 * own order, and nothing merged on the way.
 *
 * This class used to assert the opposite of its second half. Pinning a subtab as a tab of its own put a second
 * `ChatTab` over the SAME session in the strip, so the diagram drew that chat, its agents and its tasks twice
 * over ("a veces se duplican"), and `workloadsJson` filtered by session identity to hide it. Both are gone: a
 * subtab is a transcript switched inside the chat's own browser and never a tab, the strip therefore holds one
 * tab per session (`ChatTabsPanel`, pinned by `ToolWindowWiringContractTest`), and the filter came out with the
 * state it existed for.
 *
 * **The old test is not kept "just in case", and that is the point worth writing down.** It fed the builder an
 * input the product can no longer produce and asserted the builder swallowed it — a green assertion about a
 * state that cannot occur, which reads as coverage and is not. Reinstating the filter would be actively worse
 * than leaving it out: a duplicate here would be the only visible symptom of two tabs sharing a session, whose
 * other symptom (closing one disposes the other's `claude` process) shows nothing at all, so absorbing it
 * silently turns the one place the defect surfaces into the place that conceals it.
 */
class JcefSessionDataWorkloadsHeadlessTest : BasePlatformTestCase() {

    fun `test every open chat is drawn, once each and in the strip's order`() {
        val workloads = listOf(
            JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = ClaudeSession(project, "Chat")),
            JcefSessionData.Workload("tab-2", "Chat 2", selected = false, session = ClaudeSession(project, "Chat")),
            JcefSessionData.Workload("tab-3", "Git", selected = false, session = ClaudeSession(project, "Chat")),
        )
        val drawn = drawnBy(workloads)

        assertEquals(3, drawn.size)
        // Order matters and is the strip's: the diagram reads top-to-bottom in the order the bar reads
        // left-to-right, and a builder that sorted or grouped would silently break that correspondence.
        assertEquals(listOf("tab-1", "tab-2", "tab-3"), drawn.map { it.jsonObject["chatId"]!!.text() })
        assertEquals(listOf("Chat 1", "Chat 2", "Git"), drawn.map { it.jsonObject["title"]!!.text() })
    }

    fun `test a chat with no work is still a node, rather than being dropped`() {
        // An empty `tree` and `tasks` is an answer — "this chat has started nothing" — and the diagram needs
        // the node to be able to say it. Omitting a chat that happens to be idle would make the diagram
        // disagree with the tab bar about how many chats are open.
        val drawn = drawnBy(
            listOf(JcefSessionData.Workload("tab-1", "Chat 1", selected = true, session = ClaudeSession(project, "Chat"))),
        )

        assertEquals(1, drawn.size)
        assertTrue(drawn.first().jsonObject["tree"]!!.jsonArray.isEmpty())
        assertTrue(drawn.first().jsonObject["tasks"]!!.jsonArray.isEmpty())
    }

    fun `test no strip to ask means no workloads, not an invented one`() {
        // A panel outside the tab strip passes nothing. The frontend falls back to this session's own
        // `agentTree` / `backgroundTasks` and draws a single root, which it can only do if the key is empty.
        assertTrue(drawnBy(emptyList()).isEmpty())
    }

    /** The `workloads` array of one dashboard payload, with age taken out of it ([WorkloadWindow.ALL]). */
    private fun drawnBy(workloads: List<JcefSessionData.Workload>) =
        Json.parseToJsonElement(
            JcefSessionData.sessionJson(
                ClaudeSession(project, "Chat"),
                windowMinutes = WorkloadWindow.ALL,
                nowMillis = NOW,
                workloads = workloads,
            ),
        ).jsonObject["workloads"]!!.jsonArray

    /** A JSON string as its bare text — these assertions are about ids and titles, not about quoting. */
    private fun JsonElement.text() = toString().trim('"')

    private companion object {
        /** Any instant will do: these pin the projection, and [WorkloadWindow.ALL] takes age out of it. */
        const val NOW = 1_000_000_000L
    }
}
