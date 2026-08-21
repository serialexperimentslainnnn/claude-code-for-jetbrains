package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GuardViewWiringContractTest {

    @Test
    fun `the Ready message pushes the guard log, which is the one door every panel goes through`() {
        val branch = readyBranch()

        assertTrue(branch.any { it.contains("pushGuard()") }) {
            "Opening a chat, restoring one at startup, forking one and the Git chat opening itself are four " +
                "different routes onto a panel, and Ready is the only point all four cross. A Guard view " +
                "wired anywhere else is a view that is empty down three of them.\n" + branch.joinToString("\n")
        }
    }

    @Test
    fun `the page has exactly one emitter of the guard payload`() {
        val emitters = kotlinFiles()
            .filter { it.readText().contains("window.cc.guard(") }
            .map { it.name }
            .sorted()

        assertEquals(listOf("GuardFeed.kt"), emitters) {
            "window.cc.guard is emitted from more than one place: $emitters"
        }
    }

    @Test
    fun `the guard payload is built off the EDT, because reading it goes to the password safe`() {
        val feed = source("ui/GuardFeed.kt").readText()

        assertTrue(feed.contains("executeOnPooledThread")) {
            "GuardFeed reads the alert log on whatever thread asked for it. That read decodes the whole " +
                "stored array, and every UI mutation here is on the EDT."
        }
        assertTrue(feed.contains("invokeLater")) {
            "GuardFeed does not come back to the EDT to draw."
        }
    }

    @Test
    fun `the gear menu reaches the guard view, and so does the chat's own view row`() {
        val factory = source("ui/ClaudeToolWindowFactory.kt").readText()

        assertTrue(factory.contains("showGuardView")) {
            "the tool window's gear has no entry for the guard log"
        }
        assertTrue(source("ui/SecurityViews.kt").readText().contains("window.cc.openGuardView")) {
            "nothing on the host side can open the guard view, so the gear entry lands nowhere"
        }
        assertTrue(File(jcefRoot(), "app-session.js").readText().contains("'guard'")) {
            "the dashboard has no guard view for the host to open. A feature whose only door is the gear " +
                "menu is a feature nobody finds."
        }
    }

    @Test
    fun `opening the view refreshes it first, so it never opens on a stale read`() {
        val lines = source("ui/SecurityViews.kt").readLines()
        val start = lines.indexOfFirst { it.contains("fun openGuardView()") }
        assertTrue(start >= 0) { "SecurityViews no longer opens the guard view" }
        val body = lines.drop(start).take(BODY_LINES)
        val push = body.indexOfFirst { it.contains("pushGuard()") }
        val open = body.indexOfFirst { it.contains("window.cc.openGuardView") }

        assertTrue(push in 0 until open) {
            "openGuardView shows the view before refreshing it: ${body.joinToString("\n")}"
        }
    }

    @Test
    fun `both guard messages are parsed and both are dispatched`() {
        val bridge = source("ui/jcef/JcefBridge.kt").readText()
        val router = source("ui/ChatBridgeRouter.kt").readText()

        listOf("\"guardLog\"", "\"guardExplain\"").forEach {
            assertTrue(bridge.contains(it)) { "JcefBridge does not parse $it" }
        }
        assertTrue(router.contains("Msg.GuardLog")) { "nothing answers a refresh from the guard view" }
        assertTrue(router.contains("guard.explain(")) { "the question button reaches nothing" }
    }

    @Test
    fun `the question goes through the side-question path and never through the chat send`() {
        val feed = source("ui/GuardFeed.kt").readText()

        assertTrue(feed.contains("sendSideQuestion")) {
            "asking why a call was blocked is a question, not a turn of work"
        }
        assertTrue(!feed.contains(".send(")) {
            "GuardFeed sends a prompt as an ordinary message, which queues it as work"
        }
    }

    @Test
    fun `the alert tally is actually fed, or the dropped-alert alarm can never fire`() {
        val session = source("session/ClaudeSession.kt").readLines()
        val start = session.indexOfFirst { it.contains("private fun recordAlert(") }
        assertTrue(start >= 0) { "ClaudeSession no longer records guard alerts" }
        val body = session.drop(start).take(BODY_LINES)

        assertTrue(body.any { it.contains("guardLog.submitted(") }) {
            "recordAlert does not tell the tally what happened. GuardAlertLog.record answers null when the " +
                "safe is inert and drops the alert; unless that answer is counted, the view reports a " +
                "complete log while entries are being thrown away.\n" + body.joinToString("\n")
        }
    }

    @Test
    fun `the module and the stylesheet are declared, or the page silently does not serve them`() {
        val host = source("ui/jcef/JcefHost.kt").readText()

        assertTrue(host.contains("\"app-session-guard.js\"")) {
            "app-session-guard.js is not in JcefHost.appNames, so it is not served and cc.guard does not exist"
        }
        assertTrue(host.contains("\"guard.css\"")) {
            "guard.css is not in JcefHost.CSS_PARTS, so the view draws unstyled"
        }
        assertTrue(File(jcefRoot(), "app-session-guard.js").isFile)
        assertTrue(File(jcefRoot(), "css/guard.css").isFile)
    }

    private fun readyBranch(): List<String> {
        val lines = source("ui/ChatBridgeRouter.kt").readLines()
        val start = lines.indexOfFirst { it.contains("JcefBridge.Msg.Ready ->") }
        assertTrue(start >= 0) { "ChatBridgeRouter no longer handles Msg.Ready" }
        val length = lines.drop(start + 1).indexOfFirst { it == "        }" }
        assertTrue(length >= 0) { "could not find the end of the Msg.Ready branch" }
        return lines.subList(start, start + 1 + length)
    }

    private fun kotlinFiles(): List<File> =
        File(mainRoot(), "dev/lain/claudejb").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(relative: String) = File(mainRoot(), "dev/lain/claudejb/$relative").also {
        assertTrue(it.isFile) { "missing source file: $it" }
    }

    private fun mainRoot(): File = resolve("src/main/kotlin")

    private fun jcefRoot(): File = resolve("src/main/resources/jcef")

    private fun resolve(path: String): File =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isDirectory }
            ?: error("could not locate $path from ${File("").absolutePath}")

    private companion object {
        const val BODY_LINES = 40
    }
}
