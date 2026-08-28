package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootScreenUiTest : UiTestBase() {

    @Test
    fun `the waiting screens live below the tab bar and never cover a chat tab`() {
        openClaudeToolWindow()
        awaitChatPage()
        waitForWeb("the tab bar to be drawn", BAR_VISIBLE)

        val showing = js(WAITING_SCREEN)

        assertTrue(
            jsBool(WORK_BELOW_BAR),
            "the work area overlaps the tab bar (waiting screen showing: $showing) — " +
                "an overlay is back on top of #app instead of inside #work",
        )
        assertTrue(
            jsBool(PILL_IS_HITTABLE),
            "the centre of a chat pill does not hit that pill (waiting screen showing: $showing) — " +
                "something is painted over the tab bar",
        )
    }

    private companion object {
        const val BAR_VISIBLE =
            "(function () { var b = document.getElementById(\"tabsbar\"); " +
                "return String(!!b && !b.hidden && b.getBoundingClientRect().height > 0); })()"

        const val WAITING_SCREEN =
            "(function () { var boot = document.getElementById(\"boot\"); " +
                "var auth = document.getElementById(\"auth-card\"); " +
                "var up = []; " +
                "if (boot && !boot.hidden && boot.getBoundingClientRect().height > 0) { up.push(\"boot\"); } " +
                "if (auth && !auth.hidden && auth.getBoundingClientRect().height > 0) { up.push(\"auth\"); } " +
                "return up.length ? up.join(\"+\") : \"none\"; })()"

        const val WORK_BELOW_BAR =
            "(function () { var b = document.getElementById(\"tabsbar\"); " +
                "var w = document.getElementById(\"work\"); if (!b || !w) { return String(false); } " +
                "return String(w.getBoundingClientRect().top >= b.getBoundingClientRect().bottom - 1); })()"

        const val PILL_IS_HITTABLE =
            "(function () { var p = document.querySelector(\"#tabsbar .tab-rows .tab-row .tab-capsule .pill\"); " +
                "if (!p) { return String(false); } var r = p.getBoundingClientRect(); " +
                "var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); " +
                "return String(!!hit && (hit === p || p.contains(hit))); })()"
    }
}
