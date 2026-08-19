package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinning the current file as @-context shows a removable chip in the composer — and removing it gets back.
 *
 * The subject survived the rewrite; the mechanism did not. The chip used to be a Swing label in an
 * `AttachmentStripPanel`, which is one of the components 4.0.0 deleted. Today the whole path is
 * host → page → host: a tool-window action calls `AttachmentTray.addCurrentFile`, which pushes
 * `cc.attachments(...)` into the browser, the page draws `.att-label`, and the ✕ on the chip comes back as a
 * `removeAttachment` bridge message that only the host can honour.
 *
 * Neither end of that is testable on its own: the frontend suite has no host to answer the message and the
 * headless tests have no browser to draw the chip. Here both halves are real, and none of it needs a `claude`
 * process — attachments are pinned by the IDE and only travel with the next turn.
 */
class AttachmentChipUiTest : UiTestBase() {

    @Test
    fun `the current file becomes a chip, and its close button removes it`() {
        openClaudeToolWindow()
        awaitChatPage()
        // The action pins "the current file", so there has to be one: no editor, nothing to pin.
        openSampleFile()

        openGearMenu()
        clickMenuItem("Add Current File")

        waitForWeb("a chip for the open file to appear in the composer", CHIP_FOR_SAMPLE)
        assertTrue(jsInt(CHIP_COUNT) >= 1, "the attachment row is empty after pinning a file")

        findDom("//span[contains(@class,'att-x')]").clickAtCenter()

        waitForWeb("the chip to disappear once its ✕ is pressed", NO_CHIPS)
    }

    private companion object {
        const val CHIPS = "document.querySelectorAll(\"#composer .attachments .att-label\")"

        const val CHIP_COUNT = "(function () { return String($CHIPS.length); })()"

        const val CHIP_FOR_SAMPLE =
            "(function () { var c = $CHIPS; for (var i = 0; i < c.length; i++) { " +
                "if (c[i].textContent.indexOf(\"Sample.kt\") >= 0) { return String(true); } } return String(false); })()"

        /**
         * The row hides itself when the list empties (`renderAttachments`), so "gone" is checked as the row
         * being hidden or carrying no labels — either is the honest answer to "is the chip still there".
         */
        const val NO_CHIPS =
            "(function () { var row = document.querySelector(\"#composer .attachments\"); " +
                "return String(!row || row.hasAttribute(\"hidden\") || $CHIPS.length === 0); })()"
    }
}
