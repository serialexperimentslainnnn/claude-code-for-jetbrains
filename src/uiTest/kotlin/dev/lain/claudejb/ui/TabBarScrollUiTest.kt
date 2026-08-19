package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chat row scrolls — with the wheel, and by grabbing it.
 *
 * 5.5.0's bug, verbatim: **Chromium does not move a horizontal scroller with a vertical wheel**, so past a
 * handful of chats the far end of the row was simply unreachable — there is no scrollbar to aim at (the
 * platform one is a grey slab across a rounded capsule) and clicking along a row of twenty is not navigation.
 * `app-tabs.js` therefore translates the gesture (`wheel` → `scrollLeft`) and makes the row itself a handle
 * (`dragToScroll`, past a 4px slop).
 *
 * **Why this belongs in the RemoteRobot suite and not in jsdom.** Overflow is a layout fact. jsdom has no
 * layout: `scrollWidth`, `clientWidth` and `scrollLeft` are all 0 there, so the frontend suite can only check
 * that the listeners are wired, never that the row can actually be moved. Here the row overflows for real, in
 * a real tool window, at the real width — which is the only place the question has an answer.
 *
 * Two deliberate choices in how the gesture is made:
 *
 *  - **DOM events, not the OS mouse.** They hit the product's own listeners either way, and a synthetic event
 *    cannot be knocked off course by the pointer being elsewhere, by the hover menu opening after its
 *    one-second delay, or by the row moving under the cursor mid-drag. Physical input is covered for clicks
 *    by every other test in this suite.
 *  - **Reset, gesture and measurement happen in ONE expression.** Selecting a chat also centres it
 *    (`scrollIntoView`), so a repaint landing between a reset and a read would move the row on its own and
 *    the test would pass without the gesture doing anything. Measuring the delta the gesture itself caused,
 *    synchronously, is what makes this assertion about the handler rather than about timing.
 */
class TabBarScrollUiTest : UiTestBase() {

    @Test
    fun `a vertical wheel scrolls the chat row sideways`() {
        openChatsUntilRowOverflows()

        val moved = jsInt(WHEEL_DELTA)

        assertTrue(moved > 0, "a vertical wheel moved the chat row by $moved px — the gesture is not translated")
    }

    @Test
    fun `grabbing the chat row drags it sideways`() {
        openChatsUntilRowOverflows()

        val moved = jsInt(DRAG_DELTA)

        assertTrue(moved > 0, "dragging the chat row moved it by $moved px — the drag handle is gone")
    }

    /**
     * Opens chats until the row genuinely overflows, then stops.
     *
     * Adaptive rather than a fixed count because the answer depends on the tool-window width and the font
     * scale of whatever machine this runs on — and because a test that assumed an overflow it never got would
     * be asserting that a non-scrollable row does not scroll. If [MAX_CHATS] is not enough it fails and says
     * so, instead of quietly proving nothing.
     */
    private fun openChatsUntilRowOverflows() {
        openClaudeToolWindow()
        awaitChatPage()
        var opened = 0
        while (!jsBool(OVERFLOWS) && opened < MAX_CHATS) {
            newChat()
            opened++
        }
        assertTrue(
            jsBool(OVERFLOWS),
            "the chat row never overflowed after opening $opened extra chats — widen the row or raise MAX_CHATS",
        )
    }

    private companion object {
        /** Enough to overflow any sane tool-window width; each one costs a browser, so the loop stops early. */
        const val MAX_CHATS = 10

        const val CAPSULE = "document.querySelector(\"#tabsbar .tab-rows .tab-row .tab-capsule\")"

        const val OVERFLOWS =
            "(function () { var c = $CAPSULE; return String(!!c && c.scrollWidth > c.clientWidth + 1); })()"

        /** A wheel with only a vertical delta — the gesture that used to do nothing at all. */
        const val WHEEL_DELTA =
            "(function () { var c = $CAPSULE; if (!c) { return String(-1); } c.scrollLeft = 0; " +
                "var before = c.scrollLeft; " +
                "c.dispatchEvent(new WheelEvent(\"wheel\", { deltaY: 240, bubbles: true, cancelable: true })); " +
                "return String(Math.round(c.scrollLeft - before)); })()"

        /**
         * Press on the row and move left by well over the 4px slop, then release. `dragToScroll` listens for
         * `mousedown` on the capsule and for `mousemove`/`mouseup` on the document (mouse events, not pointer
         * events, deliberately — see its KDoc), so the moves bubble up from the capsule.
         */
        const val DRAG_DELTA =
            "(function () { var c = $CAPSULE; if (!c) { return String(-1); } c.scrollLeft = 0; " +
                "var before = c.scrollLeft; " +
                "var at = function (x) { return { clientX: x, clientY: 0, button: 0, bubbles: true, cancelable: true }; }; " +
                "c.dispatchEvent(new MouseEvent(\"mousedown\", at(300))); " +
                "c.dispatchEvent(new MouseEvent(\"mousemove\", at(280))); " +
                "c.dispatchEvent(new MouseEvent(\"mousemove\", at(200))); " +
                "c.dispatchEvent(new MouseEvent(\"mouseup\", at(200))); " +
                "return String(Math.round(c.scrollLeft - before)); })()"
    }
}
