package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionDashboardUiTest : UiTestBase() {

    @Test
    fun `the gear opens the dashboard and its view buttons sit in the tab bar`() {
        openClaudeToolWindow()
        awaitChatPage()

        openGearMenu()
        clickMenuItem("Session Info")

        waitForWeb("the dashboard to open in the page", DASHBOARD_OPEN)
        assertTrue(jsBool(CONVERSATION_HIDDEN), "the transcript must be hidden while the dashboard fills the area")

        assertTrue(
            jsInt(REAL_CARDS) > 0 || js(EMPTY_NOTICE).isNotBlank(),
            "the dashboard opened with neither a card nor a message naming the empty view",
        )

        assertTrue(jsBool(TOGGLES_IN_BAR), "the view buttons are not in the tab bar — they are floating again")
        assertTrue(jsBool(TOGGLES_CLEAR_OF_PILLS), "the view buttons overlap a chat tab (WCAG 2.2 SC 2.4.11)")

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

        const val REAL_CARDS =
            "(function () { return String(document.querySelectorAll(" +
                "\"#cc-dashboard .dash-card:not(.dash-empty)\").length); })()"

        const val EMPTY_NOTICE =
            "(function () { var e = document.querySelector(\"#cc-dashboard .dash-empty\"); " +
                "return e ? e.textContent.trim() : \"\"; })()"

        const val TOGGLES_IN_BAR =
            "(function () { var t = document.querySelector(\".dash-toggles\"); var b = document.getElementById(\"tabsbar\"); " +
                "return String(!!t && !!b && b.contains(t)); })()"

        const val TOGGLES_CLEAR_OF_PILLS =
            "(function () { var t = document.querySelector(\".dash-toggles\"); if (!t) { return String(false); } " +
                "var a = t.getBoundingClientRect(); " +
                "var pills = document.querySelectorAll(\"#tabsbar .tab-rows .tab-capsule .pill\"); " +
                "for (var i = 0; i < pills.length; i++) { var b = pills[i].getBoundingClientRect(); " +
                "if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) { " +
                "return String(false); } } return String(pills.length > 0); })()"
    }
}
