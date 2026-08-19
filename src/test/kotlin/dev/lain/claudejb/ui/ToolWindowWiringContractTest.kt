package dev.lain.claudejb.ui

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ToolWindowWiringContractTest {

    @Test
    fun `the strip is resolved by type, never by which content is selected`() {
        val body = bodyOf(codeOf("ui/ClaudeToolWindowFactory.kt"), "private fun tabsPanel(")

        assertTrue(body.none { it.contains("selectedContent") }) {
            "tabsPanel() is back to reading the SELECTED content. There is one content and it is the strip, " +
                "so a manager with nothing selected makes activePanel/chatTabs/contextComponent/newChat/" +
                "showGitView all answer null — and every one of those ends in a `?.` that fails silently."
        }
        assertTrue(body.any { it.contains(".contents") }) {
            "tabsPanel() no longer searches the contents for the strip:\n" + body.joinToString("\n")
        }
    }

    @Test
    fun `the tab commands are published before anything is restored`() {
        val code = codeOf("ui/ClaudeToolWindowFactory.kt")
        val published = code.indexOfFirst { it.contains("tabs.commands = commands") }
        val restored = code.indexOfFirst { it.contains("restoreOrCreate()") }

        assertTrue(published >= 0 && restored >= 0) {
            "createToolWindowContent no longer publishes the commands or no longer restores — this contract " +
                "needs rewriting, not deleting (published at $published, restored at $restored)."
        }
        assertTrue(published < restored) {
            "The commands are published AFTER the restore. For as long as that runs, ChatTabsPanel.commands " +
                "is null, so a *New chat* pressed during it and the replacement chat that closing the last " +
                "one owes the user both go through `commands?.` and do nothing at all."
        }
    }

    @Test
    fun `a new chat is shown only once its page can draw`() {
        val code = codeOf("ui/ClaudeToolWindowFactory.kt")
        val open = code.indexOfFirst { it.contains("private fun openChat(") }
        assertTrue(open >= 0) { "ClaudeToolWindowFactory no longer has an openChat" }

        val body = code.drop(open)
        val deferred = body.indexOfFirst { it.contains("whenWebReady") }
        val select = body.indexOfFirst { it.contains("tabs.select(tab)") }

        assertTrue(select >= 0) { "openChat no longer selects the tab it opened" }
        assertTrue(deferred in 0 until select) {
            "openChat selects the new tab without waiting for its page. The browser is empty at that moment, " +
                "so the user watches the whole UI build itself on screen — which is what gets reported as " +
                "\"opening a chat reloads the plugin\"."
        }
    }

    @Test
    fun `the wait for the page is bounded, and the deadline runs the block`() {
        val code = codeOf("ui/jcef/JcefHost.kt")
        val signature = code.firstOrNull { it.contains("fun whenWebReady(") }

        assertTrue(signature != null) { "JcefHost no longer offers whenWebReady — see ClaudeToolWindowFactory.openChat" }
        assertTrue(signature.orEmpty().contains("WEB_READY_TIMEOUT_MS")) {
            "whenWebReady no longer defaults to a named ceiling: `$signature`. Without one, a page that never " +
                "announces leaves everything it deferred unrun, for good."
        }
        val fires = bodyOf(code, "private fun runDeferred(")
        assertTrue(fires.any { it.contains("entry.block()") }) {
            "the deadline no longer runs what it was given, so an unannounced page silently swallows the " +
                "gesture instead of serving it late:\n" + fires.joinToString("\n")
        }
        assertTrue(fires.any { it.contains("deferred.remove(entry)") }) {
            "nothing takes the entry out of the queue before running it, which is what bounds a block to one " +
                "run when the deadline and the page's own announcement race."
        }
    }

    @Test
    fun `only one place builds a chat panel, so no two tabs can hold one session`() {
        val built = MainSources.files()
            .flatMap { file -> MainSources.codeOf(file).map { file.name to it } }
            .filter { (_, line) -> line.contains("JcefChatPanel(") && !line.contains("class JcefChatPanel(") }

        assertTrue(built.size == 1) {
            "A chat panel is constructed in ${built.size} places: ${built.map { it.first }}. Exactly one is " +
                "the contract. A second `JcefChatPanel(project, session)` over a session that already has a " +
                "tab puts two tabs on one `claude` process, and ClaudeToolWindowFactory's `closed` handler " +
                "disposes it without asking — so closing either tab kills the other's session, leaves it " +
                "painting a dead process and drops it from the restorable set."
        }
        assertTrue(built.single().first == "ClaudeToolWindowFactory.kt") {
            "The one chat panel is now built in ${built.single().first} rather than in openChat, which is " +
                "where the tab is added in the same breath. Whatever builds it owns the one-tab-per-session " +
                "invariant, and the assertion above is only meaningful while those are the same place."
        }
    }

    @Test
    fun `the strip has no second view of a chat, and the close handler has nothing to ask`() {
        val strip = codeOf("ui/ChatTabsPanel.kt")
        val revived = strip.filter { it.contains("isPinnedView") || it.contains("pinnedAgent") || it.contains("fun pin(") }
        assertTrue(revived.isEmpty()) {
            "ChatTabsPanel can hold a second view of a chat again:\n" + revived.joinToString("\n") +
                "\nA pinned view is a second tab over one session, which is the state ClaudeToolWindowFactory's " +
                "unconditional `closed` handler is safe only in the absence of."
        }

        val handler = codeOf("ui/ClaudeToolWindowFactory.kt").filter { it.contains("closed = { tab") }
        assertTrue(handler.size == 1) { "no single `closed = { tab …` in the factory: $handler" }
        assertTrue(handler.single().contains("manager.remove")) {
            "the tool window's close handler no longer removes the session: `${handler.single()}`"
        }
        assertTrue(!handler.single().contains("if ")) {
            "the close handler is conditional again: `${handler.single()}`. It disposes a `claude` process, " +
                "and the only reason it ever needed a condition was a tab that did not own its session."
        }
    }

    private fun bodyOf(code: List<String>, declaration: String): List<String> {
        val start = code.indexOfFirst { it.contains(declaration) }
        assertTrue(start >= 0) { "no `$declaration` in the sources — this contract needs rewriting, not deleting" }
        val rest = code.drop(start + 1)
        val end = rest.indexOfFirst { it.trim() == "}" }
        return listOf(code[start]) + if (end >= 0) rest.take(end) else rest
    }

    private fun codeOf(relative: String): List<String> {
        val file = File(MainSources.root("src/main/kotlin"), "dev/lain/claudejb/$relative")
        assertTrue(file.isFile) { "missing source file: $file" }
        return MainSources.codeOf(file)
    }
}
