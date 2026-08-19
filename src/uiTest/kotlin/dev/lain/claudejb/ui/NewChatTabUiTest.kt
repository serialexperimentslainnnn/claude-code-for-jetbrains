package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A second chat appears in the bar, and clicking a tab actually switches to it.
 *
 * This is the whole 5.5.0 tab architecture in one round trip, and it crosses every seam that exists between
 * the two halves of the UI: a Swing toolbar action builds a `JcefChatPanel`, [ChatTabsPanel] adds it as a
 * `CardLayout` card and pushes the chat list into **every** open page (each browser draws the whole bar and
 * marks its own entry), the click comes back as a `selectChat` bridge message, the strip swaps the visible
 * card, and both pages repaint with the selection moved.
 *
 * Nothing about that is visible to Swing — the bar is `app-tabs.js` — and nothing about it is visible to
 * jsdom either, because there is only one page per browser there and no host to round-trip through. It is
 * exactly the kind of thing this suite exists for.
 */
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

        // Back to the first chat, through the page: the click is a bridge message, and what answers it is the
        // host swapping the card — so the browser we are talking to afterwards is a DIFFERENT one.
        //
        // Waited for on THIS page before re-resolving the fixture, for the reason spelled out in
        // `UiTestBase.newChat`: the selection is pushed to every page from the same EDT event that swaps the
        // card, so seeing it here proves the swap has happened and the lookup below cannot catch the old one.
        findDom(FIRST_PILL).clickAtCenter()
        waitForWeb("the first chat to become the selected tab", FIRST_IS_CURRENT)

        web(refresh = true)
        awaitChatPage()

        assertEquals(0, jsInt(CURRENT_INDEX), "the chat that was switched to is not the one marked current")
        assertEquals(before + 1, chatPillCount(), "switching chats must not add or drop a tab")
    }

    private companion object {
        /** Single-quoted on purpose — see `UiTestBase.findDom`. */
        const val FIRST_PILL = "(//nav[@id='tabsbar']//div[contains(@class,'tab-capsule')]//button)[1]"

        const val PILLS =
            "document.querySelectorAll(\"#tabsbar .tab-rows .tab-row .tab-capsule .pill\")"

        /** Which chat pill is marked current, as an index; -1 when none is. */
        const val CURRENT_INDEX =
            "(function () { var p = $PILLS; for (var i = 0; i < p.length; i++) { " +
                "if (p[i].getAttribute(\"aria-current\") === \"true\") { return String(i); } } return String(-1); })()"

        const val FIRST_IS_CURRENT =
            "(function () { var p = $PILLS; " +
                "return String(p.length > 0 && p[0].getAttribute(\"aria-current\") === \"true\"); })()"
    }
}
