package dev.lain.claudejb.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JTextAreaFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Shared scaffolding for the RemoteRobot end-to-end suite (Layer D of the test pyramid).
 *
 * ## How these tests run
 * These tests do **not** spawn an IDE themselves. They are clients that talk to an already-running IDE
 * over HTTP. The flow is:
 *
 *   1. Launch the IDE under test with the **`robot-server`** plugin loaded, via the `runIdeForUiTests`
 *      task registered in `build.gradle.kts` (`intellijPlatformTesting.runIde`, `robotServerPlugin()`).
 *      The IDE then listens on `http://127.0.0.1:8082` (override with `-Drobot-server.port=…`).
 *   2. Run the `uiTest` Gradle task (`./gradlew uiTest -PuiTest.enabled=true`) — these JUnit5 tests connect
 *      to that port and drive the UI. See `docs/UI_TESTING.md` for the exact commands (+ Xvfb on headless CI).
 *
 * There is no display in the build sandbox, so the suite is **never executed here**; it is written to compile
 * cleanly and to run in CI nightly under Xvfb (see `docs/UI_TESTING.md`) or locally with a display.
 *
 * ## Fake `claude` binary (wired automatically)
 * The IDE under test talks to a deterministic stand-in instead of the real `claude` binary, otherwise the
 * tests would hit the network / a subscription and become non-deterministic. `runIdeForUiTests` launches the
 * IDE with `-Dclaudejb.fakeClaude=<bin/fake-claude>` and `-Dclaudejb.fakeFixture=<a JSONL fixture>`;
 * [dev.lain.claudejb.settings.ClaudeSettings] reads those properties (only when present — a no-op in shipped
 * IDEs) and routes the plugin to the fake + sets `FAKE_FIXTURE`. To exercise a different scenario per run,
 * relaunch `runIdeForUiTests` with a different `-Dclaudejb.fakeFixture=…` (override the build default).
 *
 * ## Locators
 * Locators below favour Swing component **class** + accessible text, because the plugin's composer/transcript
 * widgets are plain `JBTextArea`/`JButton`s without stable accessible names. When you wire this up for real,
 * open the **UI Robot inspector** (bundled with `robot-server`) against the running IDE and tighten each
 * XPath — the commented `// inspector:` hints mark every spot that benefits from that.
 */
abstract class UiTestBase {

    protected val remoteRobot: RemoteRobot = RemoteRobot(robotServerUrl())

    /** Generous default; CI under Xvfb is slow to paint and the binary handshake adds latency. */
    protected val longTimeout: Duration = Duration.ofSeconds(60)
    protected val shortTimeout: Duration = Duration.ofSeconds(10)

    /**
     * Opens (or focuses) the "Claude Code" tool window by clicking its stripe button, then returns the
     * tool-window container so callers can scope further lookups to it.
     *
     * The stripe button carries the tool-window id as its text/tooltip; if your IDE collapses the stripe
     * label, switch the locator to `@accessiblename` (inspector will show the real value).
     */
    protected fun openClaudeToolWindow(): CommonContainerFixture {
        // The tool window auto-opens on startup (restore-or-create), so this is idempotent: if our ChatPanel is
        // already present, just return its decorator; otherwise click the stripe button to show it (clicking the
        // stripe while open would HIDE it). The stripe is a SquareStripeButton with accessiblename "Claude Code".
        if (chatPanelPresent()) return claudeToolWindow()
        remoteRobot.find<ComponentFixture>(
            byXpath("//div[@class='SquareStripeButton' and @accessiblename='Claude Code']"),
            shortTimeout,
        ).click()
        return claudeToolWindow()
    }

    /** True when a [ChatPanel] is already in the component tree (the tool window is open). */
    private fun chatPanelPresent(): Boolean =
        remoteRobot.findAll<ComponentFixture>(byXpath("//div[@class='ChatPanel']")).isNotEmpty()

    /**
     * The Claude Code tool window content. The decorator's accessiblename is the *tab* title ("Chat N Tool
     * Window"), not the tool-window id, so we anchor on the decorator that actually contains our [ChatPanel].
     */
    protected fun claudeToolWindow(): CommonContainerFixture =
        remoteRobot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@class='InternalDecoratorImpl' and .//div[@class='ChatPanel']]"),
            longTimeout,
        )

    /**
     * The composer input — a [com.intellij.ui.components.JBTextArea] with the placeholder
     * "Ask Claude, or type / for commands". Located by Swing class; the placeholder is rendered via
     * `emptyText`, not the accessible name, so we match on the component class.
     */
    protected fun chatInput(scope: CommonContainerFixture = claudeToolWindow()): JTextAreaFixture =
        scope.textArea(
            // The transcript rows render as HtmlContent (JEditorPane), so the only JBTextArea under the tool
            // window is the composer. (`editable` is not exposed as an attribute, so don't filter on it.)
            byXpath("//div[@class='JBTextArea']"),
            shortTimeout,
        )

    /** All text currently visible under the tool window (concatenated), for assertions/waits. */
    protected fun transcriptText(scope: CommonContainerFixture = claudeToolWindow()): String =
        runCatching { scope.findAllText().joinToString("\n") { it.text } }.getOrDefault("")

    /** Types [prompt] into the composer and presses Enter to send. */
    protected fun sendPrompt(prompt: String, scope: CommonContainerFixture = claudeToolWindow()) {
        val input = chatInput(scope)
        input.click()
        input.keyboard {
            enterText(prompt)
            enter()
        }
    }

    /** Waits until [predicate] over the live transcript text holds, or fails after [longTimeout]. */
    protected fun waitForTranscript(message: String, predicate: (String) -> Boolean) {
        waitFor(longTimeout, Duration.ofMillis(500), message) {
            runCatching { predicate(transcriptText()) }.getOrDefault(false)
        }
    }

    companion object {
        /** Robot-server endpoint; override via `-Drobot-server.url` (e.g. a remote runner). */
        fun robotServerUrl(): String =
            System.getProperty("robot-server.url") ?: "http://127.0.0.1:8082"
    }
}
