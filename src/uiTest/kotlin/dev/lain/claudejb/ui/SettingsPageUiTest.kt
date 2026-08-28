package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsPageUiTest : UiTestBase() {

    @Test
    fun `the gear opens the Claude Code settings page with its launch options`() {
        openClaudeToolWindow()

        openGearMenu()
        clickMenuItem("Settings")

        val dialog = remoteRobot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@class='MyDialog']"),
            longTimeout,
        )
        waitFor(longTimeout, POLL, "the Claude Code settings page", "the settings dialog never showed our page") {
            runCatching { dialog.findAllText().any { it.text.contains("Permission mode") } }.getOrDefault(false)
        }

        dialog.comboBox("Model:")

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            dialog.comboBox("Effort:").listValues(),
            "the effort combo no longer lists the EffortLevel wire values",
        )

        dialog.button("Cancel").click()
    }
}
