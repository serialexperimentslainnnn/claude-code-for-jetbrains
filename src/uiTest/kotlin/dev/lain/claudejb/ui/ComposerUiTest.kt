package dev.lain.claudejb.ui

import com.intellij.remoterobot.utils.keyboard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

/**
 * The composer's keyboard contract, driven with a **real keyboard** against the real browser.
 *
 * Two things are under test here and only one of them is about keys:
 *
 *  1. **Keystrokes reach the page at all.** That is not a given, and it was broken for a whole release: a new
 *     tab was unusable because the `Content` declared no `preferredFocusedComponent` and because CEF keeps its
 *     own focus flag, which a freshly loaded page starts with cleared (see `JcefHost.markWebReady` and the
 *     4.3.1 notes). A jsdom test cannot see any of that; this one types through the OS and reads the value
 *     back out of the DOM.
 *  2. **Enter sends and Shift+Enter does not** (`app-composer.js` `wireInput`). The page clears the textarea
 *     itself on send, so the assertion holds whether or not a `claude` process is running — which matters,
 *     because this suite cannot count on one (see [UiTestBase]).
 */
class ComposerUiTest : UiTestBase() {

    @Test
    fun `typing reaches the composer and Enter clears it`() {
        openClaudeToolWindow()
        awaitChatPage()
        clearComposer()
        focusComposer()

        type("hola")
        waitForWeb("the typed text to reach the composer", composerHas("hola"))

        remoteRobot.keyboard { enter() }
        waitForWeb("Enter to clear the composer", composerIsEmpty())
    }

    @Test
    fun `Shift+Enter keeps a multi-line draft instead of sending it`() {
        openClaudeToolWindow()
        awaitChatPage()
        clearComposer()
        focusComposer()

        type("first line")
        remoteRobot.keyboard { pressing(KeyEvent.VK_SHIFT) { enter() } }
        type("second line")

        waitForWeb("a two-line draft to survive Shift+Enter", composerHasNewline())
        val draft = composerText()
        assertTrue(draft.contains("first line"), "the first line was lost: '$draft'")
        assertTrue(draft.contains("second line"), "the second line was lost: '$draft'")
    }

    /**
     * Empties the composer before typing, so a leftover draft from an earlier test cannot make an assertion
     * pass. Done in the page rather than with Ctrl+A/Delete: this is setup, and it must not depend on the
     * very key handling the test is about to exercise.
     */
    private fun clearComposer() {
        waitForWeb("the composer to be built", composerExists())
        js(
            "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
                "if (t) { t.value = \"\"; } return String(true); })()",
        )
    }

    private fun composerExists() =
        "(function () { return String(!!document.querySelector(\"textarea.composer-input\")); })()"

    private fun composerHas(text: String) =
        "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
            "return String(!!t && t.value.indexOf(\"$text\") >= 0); })()"

    private fun composerIsEmpty() =
        "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
            "return String(!!t && t.value.length === 0); })()"

    private fun composerHasNewline() =
        "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
            "return String(!!t && t.value.indexOf(String.fromCharCode(10)) >= 0); })()"
}
