package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        const val MAX_CHATS = 10

        const val CAPSULE = "document.querySelector(\"#tabsbar .tab-rows .tab-row .tab-capsule\")"

        const val OVERFLOWS =
            "(function () { var c = $CAPSULE; return String(!!c && c.scrollWidth > c.clientWidth + 1); })()"

        const val WHEEL_DELTA =
            "(function () { var c = $CAPSULE; if (!c) { return String(-1); } c.scrollLeft = 0; " +
                "var before = c.scrollLeft; " +
                "c.dispatchEvent(new WheelEvent(\"wheel\", { deltaY: 240, bubbles: true, cancelable: true })); " +
                "return String(Math.round(c.scrollLeft - before)); })()"

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
