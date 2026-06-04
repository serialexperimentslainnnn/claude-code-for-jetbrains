package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ActionButtonFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The tool window's title bar carries a "Close All Diffs" action (`CloseAllDiffsAction`, enabled only when the
 * plugin has diff tabs open). This test opens a couple of diffs, clicks the title-bar action, and asserts the
 * diff tabs are gone.
 *
 * The fixture scenario should drive two edits so two diff tabs open (or the same edit twice). Configure the
 * IDE-under-test per [UiTestBase].
 */
class CloseAllDiffsUiTest : UiTestBase() {

    @Test
    fun `Close All Diffs title action closes every diff tab`() {
        val toolWindow = openClaudeToolWindow()

        // Drive a couple of edits so the plugin opens diff tabs.
        sendPrompt("Edit two files so I can review the diffs", toolWindow)

        waitFor(longTimeout, Duration.ofMillis(500), "expected at least one diff tab to open") {
            openDiffViewers().isNotEmpty()
        }

        // The action renders as an ActionButton in the tool-window header; its tooltip is the action text.
        // inspector: confirm whether the title actions live under the tool-window header or a gear/overflow
        // menu on your build; if overflowed, click the "More" (⋮) button first, then the menu item.
        val closeAll: ActionButtonFixture = toolWindow.actionButton(
            byXpath("//div[@accessiblename='Close All Diffs' or @tooltiptext='Close All Diffs' or @myaction.text='Close All Diffs']"),
            shortTimeout,
        )
        closeAll.click()

        waitFor(longTimeout, Duration.ofMillis(500), "expected all diff tabs to close") {
            openDiffViewers().isEmpty()
        }
    }

    private fun openDiffViewers(): List<ComponentFixture> =
        remoteRobot.findAll(
            ComponentFixture::class.java,
            byXpath("//div[contains(@class,'DiffSplitter') or contains(@class,'SimpleDiffPanel') or contains(@class,'DiffViewer')]"),
        )
}
