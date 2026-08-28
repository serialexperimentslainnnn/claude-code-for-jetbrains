package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test

class OpenPreviousSessionUiTest : UiTestBase() {

    @Test
    fun `the gear offers previous sessions, or says there are none`() {
        openClaudeToolWindow()

        openGearMenu()
        clickMenuItem("Open Previous Session")

        waitFor(
            longTimeout,
            POLL,
            "the session chooser or the empty-history message",
            "Open Previous Session opened neither the chooser nor the 'no previous sessions' message",
        ) {
            runCatching { chooserIsUp() || emptyMessageIsUp() }.getOrDefault(false)
        }

        remoteRobot.keyboard { escape() }
    }

    private fun chooserIsUp(): Boolean =
        remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='HeavyWeightWindow']"))
            .any { window -> window.findAllText().any { it.text.contains("Open Previous Session") } }

    private fun emptyMessageIsUp(): Boolean =
        remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='MyDialog']"))
            .any { dialog -> dialog.findAllText().any { it.text.contains("No previous sessions") } }
}
