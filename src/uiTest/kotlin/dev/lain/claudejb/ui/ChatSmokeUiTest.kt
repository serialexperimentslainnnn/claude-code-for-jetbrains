package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSmokeUiTest : UiTestBase() {

    @Test
    fun `the chat tab comes up as a live web view`() {
        val strip = openClaudeToolWindow()

        assertTrue(
            strip.findAllText().none { it.text.contains("needs JCEF") },
            "the panel is showing the JCEF-unavailable fallback instead of a browser",
        )

        awaitChatPage()

        assertTrue(jsBool(HAS_CONVERSATION), "the transcript log region (#conversation) is missing")
        assertTrue(jsBool(HAS_COMPOSER), "the composer textarea was never built")

        assertTrue(chatPillCount() >= 1, "the tab bar is drawing no chats")
        assertEquals(1, jsInt(CURRENT_PILLS), "exactly one chat pill must carry aria-current=true")
    }

    private companion object {
        const val HAS_CONVERSATION =
            "(function () { return String(!!document.getElementById(\"conversation\")); })()"

        const val HAS_COMPOSER =
            "(function () { return String(!!document.querySelector(\"textarea.composer-input\")); })()"

        const val CURRENT_PILLS =
            "(function () { var r = document.querySelector(\"#tabsbar .tab-rows .tab-row\"); " +
                "return String(r ? r.querySelectorAll(\".pill[aria-current=true]\").length : 0); })()"
    }
}
