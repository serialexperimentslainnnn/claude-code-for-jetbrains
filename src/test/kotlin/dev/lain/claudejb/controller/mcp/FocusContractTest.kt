package dev.lain.claudejb.controller.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FocusContractTest {

    private val root = File("src/main/kotlin/dev/lain/claudejb/controller/mcp")

    private val sources: List<File> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && it.name !in CLICK_DRIVEN }
        .toList()
        .sortedBy { it.path }

    @Test
    fun `the scan sees the tool sources and the reveal seam`() {
        assertTrue(sources.size >= MIN_SOURCES) { "only ${sources.size} sources under $root" }
        assertTrue(sources.any { it.name == KEEPER }) { "$KEEPER is gone; the focus contract has no seam left" }
        assertTrue(sources.any { it.name == REVEAL }) { "$REVEAL is gone; tools reveal through it" }
    }

    @Test
    fun `no tool takes the user's focus`() {
        val hits = sources.filter { it.name != KEEPER }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                STEALS.firstOrNull { it.containsMatchIn(line) }?.let { "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" }
            }
        }
        assertEquals(emptyList<String>(), hits) {
            "A tool call must reveal without focusing: ToolWindow.activate(runnable, false), openFile(file, false), " +
                "openTextEditor(descriptor, false), navigate(false), setSelectedContent(content, false), and " +
                "ServiceViewManager.select(…, focus = false). Only $KEEPER may request focus, and only to give it back."
        }
    }

    @Test
    fun `the terminal tab is never switched under the user`() {
        val terminal = sources.first { it.name == "TerminalTools.kt" }.readText()
        assertTrue("window.isActive" in terminal && "window.show {" in terminal) {
            "TerminalTools must show its tab through ToolWindow.show and skip the tab switch while the Terminal is active"
        }
    }

    private companion object {
        const val KEEPER = "FocusKeeper.kt"
        const val REVEAL = "Reveal.kt"
        const val MIN_SOURCES = 40

        val CLICK_DRIVEN = setOf("IdePlaces.kt")

        val STEALS = listOf(
            Regex("""\.activate\((?:[^)]*,\s*)?true\)"""),
            Regex("""open(File|TextEditor)\([^)]*,\s*true\)"""),
            Regex("""\.navigate\(true\)"""),
            Regex("""requestFocus"""),
            Regex("""setSelectedContent\([^,()]*\)"""),
            Regex("""\.select\([^)]*true,\s*true\)"""),
            Regex("""toFrontRunContent"""),
        )
    }
}
