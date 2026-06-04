package dev.lain.claudejb.ui

import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Composer keyboard contract (see `ChatPanel.installInputKeys`):
 *  - Enter sends the prompt and clears the input.
 *  - Shift+Enter inserts a newline instead of sending (the input keeps its text, now multi-line).
 *  - Esc interrupts the active turn.
 *
 * Configure the IDE-under-test per [UiTestBase].
 */
class KeyboardShortcutsUiTest : UiTestBase() {

    @Test
    fun `Shift+Enter inserts a newline and does not send`() {
        val toolWindow = openClaudeToolWindow()
        val input = chatInput(toolWindow)
        input.click()

        input.keyboard {
            enterText("first line")
            // Shift+Enter → newline, no send.
            pressing(KeyEvent.VK_SHIFT) { enter() }
            enterText("second line")
        }

        // The composer still holds both lines (it was NOT sent).
        waitFor(shortTimeout, Duration.ofMillis(250), "expected Shift+Enter to keep a multi-line draft") {
            val text = chatInput(toolWindow).text
            text.contains("first line") && text.contains("second line") && text.contains("\n")
        }
        assertTrue(chatInput(toolWindow).text.contains("\n"), "draft should contain a newline")
    }

    @Test
    fun `Enter sends the prompt and clears the input`() {
        val toolWindow = openClaudeToolWindow()
        val input = chatInput(toolWindow)
        input.click()
        input.keyboard {
            enterText("ping")
            enter()
        }

        // After send the composer is cleared.
        waitFor(shortTimeout, Duration.ofMillis(250), "expected Enter to clear the composer after sending") {
            chatInput(toolWindow).text.isBlank()
        }
        // And the prompt shows up in the transcript.
        waitForTranscript("expected the sent prompt to appear in the transcript") { it.contains("ping") }
    }

    @Test
    fun `Esc interrupts the active turn`() {
        val toolWindow = openClaudeToolWindow()

        // Kick off a turn (the fixture should keep the turn 'active' long enough to interrupt — e.g. a slow
        // streamed reply). inspector/fixture: ensure the scenario streams slowly so Esc lands mid-turn.
        sendPrompt("Stream a long answer slowly please", toolWindow)

        // While the turn is active the info bar shows "Esc to interrupt"; wait for that, then press Esc.
        waitForTranscript("expected the turn to start (Esc-to-interrupt hint)") {
            it.contains("Esc to interrupt", ignoreCase = true) || it.contains("Brewing", ignoreCase = true)
        }
        toolWindow.keyboard { escape() }

        // After interrupt the turn is no longer active: the interrupt hint goes away.
        waitFor(longTimeout, Duration.ofMillis(500), "expected Esc to end the active turn") {
            val t = transcriptText(toolWindow)
            !t.contains("Esc to interrupt", ignoreCase = true)
        }
    }
}
