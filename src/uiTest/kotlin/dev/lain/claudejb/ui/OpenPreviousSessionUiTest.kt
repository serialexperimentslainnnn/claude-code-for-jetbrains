package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The tool-window gear menu hosts "Open Previous Session…", which lists the project's past sessions (read from
 * `~/.claude/projects`) in a popup chooser titled "Open Previous Session". This test opens the gear menu,
 * clicks the item, and asserts the chooser popup appears.
 *
 * The fixture environment must have at least one prior session file for the project (or the plugin shows an
 * info message instead — see [openPreviousSessionPopupAppears] note). Configure per [UiTestBase].
 */
class OpenPreviousSessionUiTest : UiTestBase() {

    @Test
    fun `gear menu Open Previous Session shows the chooser popup`() {
        val toolWindow = openClaudeToolWindow()

        // Click the gear/settings ActionButton in the tool-window header to open the gear DefaultActionGroup.
        // inspector: the gear is an ActionButton with the standard "settingsGroup" / gear icon; confirm its
        // accessiblename ("Show Options Menu" on most platforms) and tighten.
        val gear = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[@accessiblename='Show Options Menu' or @tooltiptext='Show Options Menu' or contains(@myicon,'settings')]"),
            shortTimeout,
        )
        gear.click()

        // The popup menu item.
        val item = remoteRobot.find<ComponentFixture>(
            byXpath("//div[contains(@text,'Open Previous Session')]"),
            shortTimeout,
        )
        item.click()

        // The chooser popup carries the title "Open Previous Session". If no sessions exist the plugin shows
        // an info dialog instead; in CI the fixture project should have a seeded session so the chooser opens.
        waitFor(longTimeout, Duration.ofMillis(500), "expected the Open Previous Session chooser to appear") {
            remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@accessiblename='Open Previous Session'] | //div[@class='HeavyWeightWindow']//div[contains(@text,'Open Previous Session')]"),
            ).isNotEmpty()
        }
    }
}
