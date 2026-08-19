package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Settings ▸ Claude Code opens, from the tool window, with its launch options on it.
 *
 * Reached through the gear's own "Settings…" item rather than through `Ctrl+Alt+S` + the search box: that is
 * the route the plugin owns and therefore the one that can break, and it exercises the gear group as well.
 *
 * **What is asserted, and what deliberately is not.** The effort list is a fixed enum (`EffortLevel`), so it
 * is asserted exactly — a page that failed to build, or a combo wired to the wrong list, cannot pass. The
 * *model* combo is only checked for being there: it is populated asynchronously from the binary's
 * `initialize` catalogue, so on a harness with no live session its contents are legitimately empty, and
 * "the list does not contain X" over an empty list is a test that cannot fail. (The model labels are pinned
 * where they can be: `JcefModelLabelTest` in the unit suite.)
 *
 * The strings are literals rather than references to `ClaudeSession.EFFORT_LEVELS`: this source set is a
 * black-box client with no IntelliJ Platform on its classpath, so naming a plugin class here would drag the
 * platform in behind it.
 */
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

        // The model combo exists and is bound to its label (FormBuilder's `labelFor`), which is what the
        // relative locator resolves — a page that stopped labelling its fields fails here.
        dialog.comboBox("Model:")

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            dialog.comboBox("Effort:").listValues(),
            "the effort combo no longer lists the EffortLevel wire values",
        )

        // Close without applying: this dialog writes into the IDE password safe, and a UI test has no
        // business changing the developer's stored configuration.
        dialog.button("Cancel").click()
    }
}
