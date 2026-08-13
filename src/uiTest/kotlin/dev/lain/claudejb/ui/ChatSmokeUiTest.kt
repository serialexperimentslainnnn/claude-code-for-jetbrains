package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The smoke test: opening the tool window gives you a **live web view**, not a dead panel.
 *
 * This is the cheapest possible guard on the failure this plugin's floor exists for. Since 4.0.0 the whole
 * chat is JCEF, and on 2026.2 the platform moved the embedded browser into a bundled plugin of its own — a
 * descriptor that does not declare `com.intellij.modules.jcef` gets no `JBCefApp` in its classloader, and
 * every chat died in `JcefHost.<init>` with a `NoClassDefFoundError`. `verifyPlugin` said "Compatible"
 * throughout, because it resolves against the whole distribution rather than the plugin's classloader.
 *
 * All four assertions here fail in that world: the tab is never built, so the strip has no browser; and if
 * JCEF is merely *disabled* rather than missing, `JcefHost` puts a Swing label in the panel instead, which is
 * the one piece of chat UI RemoteRobot can still read as text.
 *
 * Deliberately makes no claim about a *turn*: see [UiTestBase] for why nothing in this suite drives one.
 */
class ChatSmokeUiTest : UiTestBase() {

    @Test
    fun `the chat tab comes up as a live web view`() {
        val strip = openClaudeToolWindow()

        // The JCEF-unavailable fallback is a Swing label — the only chat text RemoteRobot can extract at all.
        assertTrue(
            strip.findAllText().none { it.text.contains("needs JCEF") },
            "the panel is showing the JCEF-unavailable fallback instead of a browser",
        )

        awaitChatPage()

        // The three regions the shell declares statically, and which every later test depends on.
        assertTrue(jsBool(HAS_CONVERSATION), "the transcript log region (#conversation) is missing")
        assertTrue(jsBool(HAS_COMPOSER), "the composer textarea was never built")

        // The tab bar draws this chat, and marks exactly one chat as current (`aria-current`, not colour).
        assertTrue(chatPillCount() >= 1, "the tab bar is drawing no chats")
        assertEquals(1, jsInt(CURRENT_PILLS), "exactly one chat pill must carry aria-current=true")
    }

    private companion object {
        const val HAS_CONVERSATION =
            "(function () { return String(!!document.getElementById(\"conversation\")); })()"

        const val HAS_COMPOSER =
            "(function () { return String(!!document.querySelector(\"textarea.composer-input\")); })()"

        /**
         * NB the attribute value is unquoted (`[aria-current=true]`): the expression travels inside a
         * single-quoted Nashorn string on the IDE side, so an escaped double quote would be unescaped in
         * transit and arrive as a syntax error. CSS allows a bare identifier here, so nothing is lost.
         */
        const val CURRENT_PILLS =
            "(function () { var r = document.querySelector(\"#tabsbar .tab-rows .tab-row\"); " +
                "return String(r ? r.querySelectorAll(\".pill[aria-current=true]\").length : 0); })()"
    }
}
