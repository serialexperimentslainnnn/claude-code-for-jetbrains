package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **The state the page needs in order to exist has to survive a load that failed, and be reposted on the one
 * that worked.** Two halves of one recovery, in two files, and the bug only showed when both were broken.
 *
 * REGRESSION THIS PINS. `JcefHost.onLoadEnd` drained the queued host→web pushes on every load-end, without
 * asking whether the load had delivered anything — so a dead rung of the delivery ladder spent the queue, and
 * `promote()` handed the next rung an empty one. Meanwhile the `Ready` message re-pushed the theme, the meta
 * state, the permissions, the tray, the dashboard, MCP, the version, Git and the whole transcript — and not
 * the tab bar, whose only emitter is [ChatAgentTabs.render], reached otherwise only from events (a chat
 * added, an agent scanned, a tab revealed or closed). A page that came up between two of those events
 * therefore had `chats == []`, and `app-tabs.js` hides `#tabsbar` when there is nothing in it — taking the
 * dashboard's Chat/Workloads/Git/Plan buttons with it, because they are appended into that same node.
 *
 * The user-visible result was a chat with neither tabs nor dashboard views, in the delivery route that exists
 * so that Remote Development works at all.
 *
 * Deliberately a source scan, like [InitOrderContractTest] and for the same reason: reaching either code path
 * for real needs a live IDE and a JCEF browser (and, for the interesting case, a browser whose load fails).
 * Reading the two files costs nothing and pins the decision rather than the plumbing.
 */
class PageStateRecoveryContractTest {

    @Test
    fun `a load that did not deliver the page must not drain the queued pushes`() {
        val lines = source("ui/jcef/JcefHost.kt").readLines()
        val end = lines.indexOfFirst { it.contains("override fun onLoadEnd(") }
        assertTrue(end >= 0) { "JcefHost no longer has an onLoadEnd — this contract needs rewriting, not deleting" }

        val body = lines.drop(end)
        val guard = body.indexOfFirst { it.contains("pageArrived(") }
        val drain = body.indexOfFirst { it.contains("pending") }

        assertTrue(guard >= 0) {
            "onLoadEnd no longer asks pageArrived() whether the page actually arrived. Without that question " +
                "a failed load drains the queue into a page that cannot run it, and the next rung of the " +
                "PageRoute ladder comes up with nothing to draw."
        }
        assertTrue(drain > guard) {
            "onLoadEnd touches the pending queue before deciding whether the page arrived."
        }
        assertTrue(body.subList(guard, drain).any { it.trim() == "return" }) {
            "the pageArrived() check must RETURN when the page did not arrive — logging it and carrying on " +
                "spends the queue exactly as before."
        }
    }

    /**
     * The other half of the same guard: the status code alone cannot tell a dead rung from a live one, because
     * Chromium answers a failed navigation with its own error page and a plausible-looking end. The verdict
     * comes from `onLoadError`, and it has to be CLEARED when the next navigation starts or the first failure
     * would condemn every load after it.
     */
    @Test
    fun `the failure verdict is recorded, cleared and actually consulted`() {
        val text = source("ui/jcef/JcefHost.kt").readText()
        listOf(
            "override fun onLoadError(" to
                "nothing records that a load failed, so an unreachable route reads as a delivered page",
            "override fun onLoadStart(" to
                "nothing clears the failure verdict, so one failed load would condemn every load after it",
            "pageArrived(httpStatusCode, mainFrameLoadFailed)" to
                "onLoadEnd no longer consults the failure verdict it was given",
        ).forEach { (needle, why) ->
            assertTrue(text.contains(needle)) { "$why (looked for `$needle`)" }
        }
    }

    @Test
    fun `the Ready message re-pushes the tab bar, like everything else the page owes`() {
        val lines = source("ui/ChatBridgeRouter.kt").readLines()
        val start = lines.indexOfFirst { it.contains("JcefBridge.Msg.Ready ->") }
        assertTrue(start >= 0) { "ChatBridgeRouter no longer handles Msg.Ready" }
        val length = lines.drop(start + 1).indexOfFirst { it == "        }" }
        assertTrue(length >= 0) { "could not find the end of the Msg.Ready branch" }
        val branch = lines.subList(start, start + 1 + length)

        assertTrue(branch.any { it.contains("agentTabs.render()") }) {
            "the Ready branch re-pushes everything the page needs EXCEPT the tab bar. A page that reloaded, " +
                "or that came up on a later rung of the delivery ladder, is then drawn with an empty chat " +
                "list — and the page hides #tabsbar entirely, dashboard view buttons included.\n" +
                branch.joinToString("\n")
        }
    }

    /**
     * The premise of the test above: `render()` is the tab-bar push because it is the only thing that emits
     * `window.cc.tabs`. A second emitter would not be wrong in itself, but it would mean this contract is
     * pinning the wrong call — so it fails here, where the message can say so, rather than passing hollow.
     */
    @Test
    fun `the tab bar has exactly one emitter, and it is the one Ready calls`() {
        val emitters = File(mainRoot(), "dev/lain/claudejb").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("window.cc.tabs(") }
            .map { it.name }
            .toList()
        assertEquals(listOf("ChatAgentTabs.kt"), emitters) {
            "window.cc.tabs is emitted from more than one place: $emitters"
        }
    }

    private fun source(relative: String) = File(mainRoot(), "dev/lain/claudejb/$relative").also {
        assertTrue(it.isFile) { "missing source file: $it" }
    }

    /** Resolves `src/main/kotlin` whether the test runs from the module dir or the repo root. */
    private fun mainRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
