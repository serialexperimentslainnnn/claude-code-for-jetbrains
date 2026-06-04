package dev.lain.claudejb.ui

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * When Claude's reply contains a `path:line` reference (e.g. `src/Foo.kt:42`), the plugin renders it as a
 * clickable `jb://open` link confined to the project root. Clicking it opens the file in an editor at that
 * line. This test asks the fixture for a reply carrying such a reference, clicks the link, and asserts an
 * editor tab for that file opens.
 *
 * The IDE-under-test must be opened on a project that actually contains `src/Foo.kt` (the fixture project),
 * otherwise `isWithinRoot` rejects the link and nothing opens. Configure per [UiTestBase].
 */
class JumpToCodeUiTest : UiTestBase() {

    @Test
    fun `clicking a path-line link opens the file in the editor`() {
        val toolWindow = openClaudeToolWindow()

        sendPrompt("Where is the bug? Reference the exact line.", toolWindow)

        // Wait for the rendered link. Linkified references render inside the transcript's HTML pane
        // (JEditorPane/JTextPane); the link text is the `src/Foo.kt:42` reference.
        // inspector: the transcript uses an HTML JEditorPane; a hyperlink there is not a separate Swing
        // component, so we locate the pane and click the link via its text anchor. If your renderer emits a
        // dedicated link component, switch to byText('src/Foo.kt:42').
        waitForTranscript("expected a path:line reference to render") { it.contains("Foo.kt:42") }

        val transcriptPane = toolWindow.find(
            ComponentFixture::class.java,
            byXpath("//div[@class='JEditorPane' or @class='JTextPane' or contains(@class,'MarkdownText')]"),
            longTimeout,
        )
        // Click on the link anchor. `text(...)` resolves the on-screen location of the substring so the click
        // lands on the hyperlink.
        transcriptPane.findText("src/Foo.kt:42").click()

        // The editor opens a tab whose title is the file name.
        // inspector: editor tabs are `EditorTabs`/`SingleHeightTabs` children; the tab label is a
        // `EditorTabLabel`/`TabLabel` carrying the file name.
        waitFor(longTimeout, Duration.ofMillis(500), "expected an editor tab for Foo.kt to open") {
            remoteRobot.findAll<ComponentFixture>(
                byXpath("//div[@class='EditorTabLabel' and @accessiblename='Foo.kt'] | //div[@class='TabLabel' and contains(@text,'Foo.kt')]"),
            ).isNotEmpty()
        }
    }
}
