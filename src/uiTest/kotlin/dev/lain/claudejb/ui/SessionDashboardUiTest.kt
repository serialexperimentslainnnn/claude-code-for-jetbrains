package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gear's "Session Info" opens the **dashboard in the page**, and its view buttons live in the tab bar.
 *
 * Two changes are pinned here, and both are recent enough to be worth a live test:
 *
 *  - The old plain-text dialogs (Context…, Cost…, Account…, MCP…) are gone; the gear now opens the formatted
 *    JCEF dashboard. A test that still went looking for a Swing dialog with an "Email" row — which is what
 *    this file used to do — was testing something the product removed.
 *  - In 5.5.0 the Chat / Session / Workloads buttons moved out of a `position: fixed` corner stack and INTO
 *    the tab bar as flex items. As a floating stack they sat on top of the transcript and, with a few chats
 *    open, on top of the tabs themselves — and overlapping a focusable control is **WCAG 2.2 SC 2.4.11
 *    (Focus Not Obscured)**. Being in the flow makes that impossible by construction rather than by keeping a
 *    `padding-right` in sync with the width of three words, so the test asserts the construction: the stack
 *    is a child of the bar, and it intersects no chat pill.
 *
 * The dashboard renders from a null-safe payload (each card omits itself when its data is absent), so this
 * works with or without a live `claude` process — see [UiTestBase] on why that matters.
 */
class SessionDashboardUiTest : UiTestBase() {

    @Test
    fun `the gear opens the dashboard and its view buttons sit in the tab bar`() {
        openClaudeToolWindow()
        awaitChatPage()

        openGearMenu()
        clickMenuItem("Session Info")

        waitForWeb("the dashboard to open in the page", DASHBOARD_OPEN)
        assertTrue(jsBool(CONVERSATION_HIDDEN), "the transcript must be hidden while the dashboard fills the area")
        assertTrue(jsInt(DASH_CARDS) > 0, "the dashboard opened with no cards in it")

        assertTrue(jsBool(TOGGLES_IN_BAR), "the view buttons are not in the tab bar — they are floating again")
        assertTrue(jsBool(TOGGLES_CLEAR_OF_PILLS), "the view buttons overlap a chat tab (WCAG 2.2 SC 2.4.11)")

        // "Chat" is a way out, not a mode of the others: pressing it must give the transcript back.
        findDom("//button[contains(@class,'dash-exit')]").clickAtCenter()
        waitForWeb("the dashboard to close again", DASHBOARD_CLOSED)
    }

    private companion object {
        const val DASHBOARD_OPEN =
            "(function () { var d = document.getElementById(\"cc-dashboard\"); " +
                "return String(!!d && !d.hidden); })()"

        const val DASHBOARD_CLOSED =
            "(function () { var d = document.getElementById(\"cc-dashboard\"); " +
                "var c = document.getElementById(\"conversation\"); " +
                "return String(!!d && d.hidden && !!c && !c.hidden); })()"

        const val CONVERSATION_HIDDEN =
            "(function () { var c = document.getElementById(\"conversation\"); return String(!!c && c.hidden); })()"

        const val DASH_CARDS =
            "(function () { return String(document.querySelectorAll(\"#cc-dashboard .dash-card\").length); })()"

        const val TOGGLES_IN_BAR =
            "(function () { var t = document.querySelector(\".dash-toggles\"); var b = document.getElementById(\"tabsbar\"); " +
                "return String(!!t && !!b && b.contains(t)); })()"

        /** No chat pill's rectangle may intersect the view buttons' rectangle. */
        const val TOGGLES_CLEAR_OF_PILLS =
            "(function () { var t = document.querySelector(\".dash-toggles\"); if (!t) { return String(false); } " +
                "var a = t.getBoundingClientRect(); " +
                "var pills = document.querySelectorAll(\"#tabsbar .tab-rows .tab-capsule .pill\"); " +
                "for (var i = 0; i < pills.length; i++) { var b = pills[i].getBoundingClientRect(); " +
                "if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) { " +
                "return String(false); } } return String(pills.length > 0); })()"
    }
}
