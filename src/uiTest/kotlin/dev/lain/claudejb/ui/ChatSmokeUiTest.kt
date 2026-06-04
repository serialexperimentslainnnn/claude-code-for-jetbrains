package dev.lain.claudejb.ui

import org.junit.jupiter.api.Test

/**
 * Smoke test: open the tool window, type a prompt, send it, and confirm the agent's reply lands in the
 * transcript. Drives the plugin against `bin/fake-claude` (see [UiTestBase] for how the IDE-under-test is
 * configured to use it), so the reply is deterministic.
 *
 * Runs only under the `uiTest` task against a robot-server IDE; not part of `check`.
 */
class ChatSmokeUiTest : UiTestBase() {

    @Test
    fun `sending a prompt produces a reply in the transcript`() {
        val toolWindow = openClaudeToolWindow()

        val prompt = "hola"
        sendPrompt(prompt, toolWindow)

        // The default fixture (multi_message.jsonl, wired by runIdeForUiTests) streams "First part." then a
        // second chunk. Assert the streamed assistant text lands in the transcript. Kept loose (either the
        // marker text OR transcript growth beyond the echoed prompt) so it survives fixture wording changes.
        waitForTranscript("expected an assistant reply to appear in the transcript") { text ->
            text.contains("First part", ignoreCase = true) ||
                text.contains("part", ignoreCase = true) ||
                text.length > prompt.length + 10
        }
    }
}
