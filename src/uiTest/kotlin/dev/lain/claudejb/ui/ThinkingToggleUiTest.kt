package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Extended thinking is a launch flag toggled by the "thinking" chip in the composer. With it on, the agent's
 * reply includes a collapsible "Thought process" block; Ctrl+O collapses (and re-expands) every such block,
 * mirroring the CLI.
 *
 * Toggling the chip restarts the session via `--resume`, so the test waits for the reply after re-sending.
 * The fixture must emit a `thinking` content block when launched with the thinking flags. Configure per
 * [UiTestBase].
 */
class ThinkingToggleUiTest : UiTestBase() {

    @Test
    fun `enabling thinking shows Thought process and Ctrl+O collapses it`() {
        val toolWindow = openClaudeToolWindow()

        // Click the thinking chip. inspector: the composer chips render as small labelled buttons; confirm the
        // chip text ("Thinking"/"Think") and tighten the locator.
        val thinkingChip = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[contains(@text,'Think') or contains(@accessiblename,'Think')]"),
            shortTimeout,
        )
        thinkingChip.click()

        sendPrompt("Reason carefully about 2 + 2 and explain.", toolWindow)

        waitForTranscript("expected a 'Thought process' block to appear") { it.contains("Thought process") }

        // Ctrl+O collapses all reasoning blocks. The block header stays ("▸ Thought process"), but the body
        // text is hidden. We assert the toggle responds by checking the disclosure glyph flips to collapsed.
        toolWindow.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_O) }

        waitFor(longTimeout, Duration.ofMillis(500), "expected Ctrl+O to collapse the Thought process block") {
            // Collapsed header uses the ▸ glyph; expanded uses ▾ (see ChatMessageViews.toggle.text).
            transcriptText(toolWindow).contains("▸ Thought process")
        }

        // Ctrl+O again re-expands.
        toolWindow.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_O) }
        waitFor(longTimeout, Duration.ofMillis(500), "expected Ctrl+O to re-expand the Thought process block") {
            transcriptText(toolWindow).contains("▾ Thought process")
        }
    }
}
