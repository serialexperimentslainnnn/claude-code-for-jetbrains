package dev.lain.claudejb.view.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LogViewWiringContractTest {

    @Test
    fun `the page has exactly one emitter of the log payload`() {
        val emitters = kotlinFiles()
            .filter { it.readText().contains("window.cc.log(") }
            .map { it.name }
            .sorted()

        assertEquals(listOf("LogFeed.kt"), emitters) { "window.cc.log is emitted from more than one place: $emitters" }
    }

    @Test
    fun `the log payload is built off the EDT and drawn on it`() {
        val feed = source("view/log/LogFeed.kt").readText()

        assertTrue(feed.contains("executeOnPooledThread")) {
            "LogFeed reads the ring and builds the report on whatever thread asked; that is the EDT when the page asks."
        }
        assertTrue(feed.contains("edt(panel.project)")) { "LogFeed does not come back to the EDT to draw." }
        assertTrue(feed.contains("CopyPasteManager")) { "the Copy button reaches no clipboard" }
    }

    @Test
    fun `the three log messages are parsed and all three are dispatched`() {
        val bridge = source("model/bridge/JcefBridge.kt").readText()
        val handler = source("controller/bridge/BridgeLog.kt").readText()

        listOf("\"logLines\"", "\"logDebug\"", "\"logCopy\"").forEach {
            assertTrue(bridge.contains(it)) { "JcefBridge does not parse $it" }
        }
        listOf("Msg.LogLines", "Msg.LogDebug", "Msg.LogCopy").forEach {
            assertTrue(handler.contains(it)) { "nothing answers $it from the Log view" }
        }
    }

    @Test
    fun `the page's own console reaches the log the view reads`() {
        assertTrue(source("view/jcef/JcefHost.kt").readText().contains("onConsoleMessage")) {
            "a CSP rejection or a script error in the page never reaches the ring, so the Log view cannot show it"
        }
    }

    @Test
    fun `the modules and the stylesheet are declared, or the page silently does not serve them`() {
        val assembly = source("view/jcef/PageAssembly.kt").readText()

        listOf("models/log/state.js", "views/log/entries.js", "controllers/log/log.js").forEach {
            assertTrue(assembly.contains("\"$it\"")) { "$it is not in PageAssembly.appNames, so it is not served" }
            assertTrue(File(tsRoot(), it.replace(".js", ".ts")).isFile) { "$it has no source" }
        }
        assertTrue(assembly.contains("\"views/log/view.css\"")) { "views/log/view.css is not in PageAssembly.CSS_PARTS" }
        assertTrue(File(jcefRoot(), "css/views/log/view.css").isFile)
        assertTrue(File(tsRoot(), "models/panel/state.ts").readText().contains("log: {")) {
            "the dashboard has no log view for the button to open"
        }
    }

    private fun kotlinFiles(): List<File> =
        File(mainRoot(), "dev/lain/claudejb").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun source(relative: String) = File(mainRoot(), "dev/lain/claudejb/$relative").also {
        assertTrue(it.isFile) { "missing source file: $it" }
    }

    private fun mainRoot(): File = resolve("src/main/kotlin")

    private fun jcefRoot(): File = resolve("src/main/resources/jcef")

    private fun tsRoot(): File = resolve("src/main/ts/jcef")

    private fun resolve(path: String): File =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isDirectory }
            ?: error("could not locate $path from ${File("").absolutePath}")
}
