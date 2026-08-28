package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NewChatTabUiTest : UiTestBase() {

    @Test
    fun `New Chat adds a tab and clicking the first one switches back to it`() {
        openClaudeToolWindow()
        awaitChatPage()
        val before = chatPillCount()

        newChat()

        assertEquals(before + 1, chatPillCount(), "the new chat did not reach the tab bar")
        assertEquals(
            chatPillCount() - 1,
            jsInt(CURRENT_INDEX),
            "the newly opened chat should be the selected one",
        )

        findDom(FIRST_PILL).clickAtCenter()
        waitForWeb("the first chat to become the selected tab", FIRST_IS_CURRENT)

        web(refresh = true)
        awaitChatPage()

        assertEquals(0, jsInt(CURRENT_INDEX), "the chat that was switched to is not the one marked current")
        assertEquals(before + 1, chatPillCount(), "switching chats must not add or drop a tab")
    }

    private companion object {
        const val FIRST_PILL = "(//nav[@id='tabsbar']//div[contains(@class,'tab-capsule')]//button)[1]"

        const val PILLS =
            "document.querySelectorAll(\"#tabsbar .tab-rows .tab-row .tab-capsule .pill\")"

        const val CURRENT_INDEX =
            "(function () { var p = $PILLS; for (var i = 0; i < p.length; i++) { " +
                "if (p[i].getAttribute(\"aria-current\") === \"true\") { return String(i); } } return String(-1); })()"

        const val FIRST_IS_CURRENT =
            "(function () { var p = $PILLS; " +
                "return String(p.length > 0 && p[0].getAttribute(\"aria-current\") === \"true\"); })()"
    }
}
