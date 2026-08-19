package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun mainRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
