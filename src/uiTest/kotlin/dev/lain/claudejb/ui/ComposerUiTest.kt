package dev.lain.claudejb.ui

import com.intellij.remoterobot.utils.keyboard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

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
