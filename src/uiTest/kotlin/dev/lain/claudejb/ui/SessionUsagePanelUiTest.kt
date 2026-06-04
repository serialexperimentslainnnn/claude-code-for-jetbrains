package dev.lain.claudejb.ui

import org.junit.jupiter.api.Test

/**
 * E7 graphical consumption readout: after a turn the [SessionUsagePanel] above the composer shows the honest
 * session output figure ("Output: N") and, once the binary reports it, a "Context" bar — replacing the old loose
 * quota labels. We assert the "Output:" label appears after a reply (the fake fixture emits usage with the result).
 *
 * The panel paints its text via Graphics2D, but RemoteRobot's `findAllText` reads painted strings, so the label is
 * still discoverable. Tighten with the inspector if the painted text isn't surfaced on your platform.
 */
class SessionUsagePanelUiTest : UiTestBase() {

    @Test
    fun `usage panel shows the session output figure after a turn`() {
        val toolWindow = openClaudeToolWindow()
        sendPrompt("Say hello.", toolWindow)
        waitForTranscript("expected the assistant to reply") { it.isNotBlank() }
        waitForTranscript("expected the usage panel to show an Output figure") { it.contains("Output:") }
    }
}
