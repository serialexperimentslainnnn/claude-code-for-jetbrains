package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * After an Edit tool call, every reviewable tool card keeps a "View diff" affordance (a link button).
 * Clicking it opens the old↔new diff in an editor tab. This test asks the fake-claude fixture to perform an
 * edit, then clicks "View diff" and asserts a diff tab opens.
 *
 * The fixture scenario must include an `assistant` message with an `Edit`/`Write` tool_use so the card and
 * its snapshot exist. Configure the IDE-under-test as described in [UiTestBase].
 */
class ViewDiffUiTest : UiTestBase() {

    @Test
    fun `View diff on a tool card opens a diff tab`() {
        val toolWindow = openClaudeToolWindow()

        sendPrompt("Edit the file and show me the change", toolWindow)

        // Wait for the tool card's "View diff" link. It is a clickable label/link button, not a JButton —
        // inspector: confirm the rendered class (LinkLabel / ActionLink / a custom label) and tighten here.
        val viewDiff = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[@text='View diff' or @accessiblename='View diff' or @visible_text='View diff']"),
            longTimeout,
        )
        viewDiff.click()

        // A diff opens as an editor tab. The plugin uses SimpleDiffRequest → ChainDiffVirtualFile opened via
        // FileEditorManager (an editor tab, not a modal window), so it shows up in the EditorTabs strip and a
        // DiffSplitter/DiffContentPanel appears in the editor area.
        // inspector: verify the diff viewer class on your platform build (e.g. `SimpleDiffPanel`,
        // `DiffSplitter`, `OnesideDiffViewer`); the OR-set below tolerates the common ones.
        waitFor(longTimeout, Duration.ofMillis(500), "expected a diff viewer to open in the editor area") {
            remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[contains(@class,'DiffSplitter') or contains(@class,'SimpleDiffPanel') or contains(@class,'DiffViewer') or @accessiblename='Editor for diff']"),
            ).isNotEmpty()
        }
    }
}
