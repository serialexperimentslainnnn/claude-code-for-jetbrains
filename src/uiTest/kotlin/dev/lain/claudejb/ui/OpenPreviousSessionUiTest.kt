package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test

/**
 * The gear's "Open Previous Session…" answers — with the chooser, or by saying there is nothing to choose.
 *
 * Session history is read from the binary's own files (`~/.claude/projects/<cwd-encoded>/…`), so whether the
 * sandbox project has any is a property of the machine, not of the plugin: a fresh CI runner has none, a
 * developer's box may. Both outcomes are real product behaviour and both are asserted, which is what keeps
 * this test honest on either machine — the failure it catches is the one that matters, an action that opens
 * nothing at all (the popup chooser is built off the EDT and handed back through two `invokeLater` hops, so
 * "nothing happens" is a genuine failure mode).
 *
 * It deliberately does not pick a session: opening one would `--resume` it and change what the rest of the
 * suite is looking at.
 */
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

        // Leave nothing on screen for the next test: Esc closes either of them.
        remoteRobot.keyboard { escape() }
    }

    /** The chooser popup: a `HeavyWeightWindow` whose title is the one `TabSessionCommands` sets. */
    private fun chooserIsUp(): Boolean =
        remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='HeavyWeightWindow']"))
            .any { window -> window.findAllText().any { it.text.contains("Open Previous Session") } }

    /** The honest empty answer — a plain info dialog, not a chooser with nothing in it. */
    private fun emptyMessageIsUp(): Boolean =
        remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='MyDialog']"))
            .any { dialog -> dialog.findAllText().any { it.text.contains("No previous sessions") } }
}
