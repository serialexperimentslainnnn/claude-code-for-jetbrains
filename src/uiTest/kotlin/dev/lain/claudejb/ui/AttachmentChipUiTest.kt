package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentChipUiTest : UiTestBase() {

    @Test
    fun `the current file becomes a chip, and its close button removes it`() {
        openClaudeToolWindow()
        awaitChatPage()
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

        const val NO_CHIPS =
            "(function () { var row = document.querySelector(\"#composer .attachments\"); " +
                "return String(!row || row.hasAttribute(\"hidden\") || $CHIPS.length === 0); })()"
    }
}
