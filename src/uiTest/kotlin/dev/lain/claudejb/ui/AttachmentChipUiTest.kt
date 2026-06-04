package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * E8 attachments: pinning the current file as @-context (the gear "Add Current File as @-context" action, which
 * routes to `ChatPanel.addAttachment`) must show a removable chip in the composer's attachment strip — carrying
 * the file's basename with the `@` marker — instead of sending immediately. Sending then clears the strip.
 *
 * The chip is a plain label inside [AttachmentStripPanel]; locate it by its `@`-prefixed text. Open the UI Robot
 * inspector against the running IDE to tighten the locator if several labels match.
 */
class AttachmentChipUiTest : UiTestBase() {

    @Test
    fun `adding current file as context pins an @ chip that clears on send`() {
        val toolWindow = openClaudeToolWindow()

        // Invoke the gear action via the tool-window header gear menu. inspector: the gear is an ActionButton
        // with accessiblename "Show Options Menu" / "More"; the item label is "Add Current File as @-context".
        val gear = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[@accessiblename='Show Options Menu' or @accessiblename='More' or @myicon='gearPlain.svg']"),
            shortTimeout,
        )
        gear.click()
        val item = remoteRobot.find(
            ComponentFixture::class.java,
            byXpath("//div[contains(@text,'Add Current File as')]"),
            shortTimeout,
        )
        item.click()

        // A chip with the @-marked file name should now be pinned in the composer.
        waitFor(longTimeout, Duration.ofMillis(500), "expected an @-context chip in the attachment strip") {
            transcriptText(toolWindow).contains("@")
        }

        // Sending a prompt should clear the strip (the attachment travels with the turn).
        sendPrompt("Use the attached file.", toolWindow)
        waitForTranscript("expected the prompt to be sent") { it.contains("Use the attached file.") }
    }
}
