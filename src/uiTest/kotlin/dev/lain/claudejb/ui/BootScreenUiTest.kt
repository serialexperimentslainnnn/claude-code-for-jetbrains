package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The waiting screens never cover the chat tabs.
 *
 * The regression this pins is 5.5.0's: `#boot` was `inset: 0` over the whole of `#app`, so **a chat that was
 * still starting covered the tab bar** and you could not switch to another one while it booted — the tabs
 * were there, drawn, and unclickable. The fix was structural: the boot screen and the sign-in card live
 * inside `#work` (today, as rows of `#conversation`), which is a sibling *below* `#tabsbar`, so they cannot
 * cover something they do not own.
 *
 * Two assertions, because "does not cover" has two failure modes and only checking one of them lets the other
 * back in:
 *
 *  1. **Geometry** — `#work` starts at or below the bottom of `#tabsbar`. An overlay that goes back to
 *     `position: fixed; inset: 0` fails here even if it happens to be transparent at the top.
 *  2. **Hit testing** — the centre of a chat pill really does hit that pill. This is the user's question
 *     ("can I click my other chat?"), and it catches the case geometry misses: something stretched over the
 *     bar with a higher `z-index` and no bounding box of its own to give it away.
 *
 * Both hold whichever of the waiting screens is up, which is the point: the invariant is about the layout,
 * not about the state. The test reports which screen was showing when it ran, so a failure says what the page
 * was doing at the time.
 */
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

        /** Which waiting screen is on screen right now — for the failure message, not for the assertion. */
        const val WAITING_SCREEN =
            "(function () { var boot = document.getElementById(\"boot\"); " +
                "var auth = document.getElementById(\"auth-card\"); " +
                "var up = []; " +
                "if (boot && !boot.hidden && boot.getBoundingClientRect().height > 0) { up.push(\"boot\"); } " +
                "if (auth && !auth.hidden && auth.getBoundingClientRect().height > 0) { up.push(\"auth\"); } " +
                "return up.length ? up.join(\"+\") : \"none\"; })()"

        /** One pixel of tolerance: sub-pixel layout must not be the thing that decides this. */
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
