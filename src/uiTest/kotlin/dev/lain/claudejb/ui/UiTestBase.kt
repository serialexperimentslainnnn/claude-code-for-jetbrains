package dev.lain.claudejb.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JCefBrowserFixture
import com.intellij.remoterobot.search.locators.Locator
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/**
 * Shared scaffolding for the RemoteRobot end-to-end suite (Layer D of the test pyramid).
 *
 * ## How these tests run
 * These tests do **not** spawn an IDE themselves. They are clients that talk to an already-running IDE over
 * HTTP:
 *
 *   1. Launch the IDE under test with the **`robot-server`** plugin loaded, via the `runIdeForUiTests` task
 *      registered in `build.gradle.kts` (`intellijPlatformTesting.runIde`, `robotServerPlugin()`). The IDE
 *      then listens on `http://127.0.0.1:8082` (override with `-Drobot-server.url=…`).
 *   2. Run the `uiTest` Gradle task (`./gradlew uiTest -PuiTest.enabled=true`) — these JUnit5 tests connect to
 *      that port and drive the UI. See `docs/UI_TESTING.md` for the exact commands (+ Xvfb on headless CI).
 *
 * There is no display in the build sandbox, so the suite is **never executed there**; it is written to compile
 * cleanly and to run nightly under Xvfb or locally with a display.
 *
 * ## WHERE THE UI ACTUALLY IS — read this before adding a locator
 * Since 4.0.0 the chat is an embedded Chromium web app (JCEF), and since 5.5.0 so is the **tab bar**. The
 * Swing chat UI that the first version of this suite drove — `ChatPanel`, `TranscriptView`, the composer
 * `JBTextArea`, the tray/strip panels, and later the two Swing tab strips — **does not exist any more**.
 * RemoteRobot's component tree and its painted-text extractor therefore see almost nothing of the product:
 * a JCEF browser paints one image, not Swing components with strings in them, so `findAllText()` over the
 * tool window returns nothing about the transcript, the composer or the tabs.
 *
 * What is reachable, and how:
 *
 *  - **Swing** — the tool-window stripe button, [ChatTabsPanel] (which draws nothing but owns the chats), the
 *    tool-window title actions ([titleAction]) and its gear menu ([openGearMenu]/[clickMenuItem]), the
 *    Settings dialog, IDE notifications, editor tabs and the native diff viewers.
 *  - **DOM** — everything else, through JetBrains' own [JCefBrowserFixture] ([web], [js], [findDom]). It
 *    injects a `JBCefJSQuery` into the page and evaluates JavaScript through CEF's host API, which is
 *    **not** subject to the page CSP (the same reason `JcefHost.exec` works against a hash-pinned
 *    `script-src`). So the assertions in this suite are made against the real DOM, in the real browser, with
 *    real layout — which is precisely what the jsdom frontend suite (`npm test`) cannot check.
 *
 * ### Two preconditions the harness cannot satisfy by itself
 *  1. **`-Dide.browser.jcef.jsQueryPoolSize=10000`** must be on the IDE-under-test's command line (it is a
 *     documented requirement of [JCefBrowserFixture]: creating a JS query against an already-loaded browser
 *     needs pre-reserved callback slots). Without it every DOM-level test fails at fixture construction —
 *     loudly, with "Can't find cef browser" or an IllegalStateException, never silently green.
 *  2. **An identity.** `ClaudeSession.start()` refuses to launch without a credential (`AuthGate
 *     .hasCredential`), and `bin/fake-claude` has no `auth status` surface to answer with, so on a machine
 *     that holds no real credential in the IDE password safe no session is ever started and the tab shows the
 *     sign-in card. Every test here is therefore written to assert something that is true **whether or not a
 *     turn can run**; anything that needs a live reply (a tool card, a diff, a rewind, an agent tree) is not
 *     in this suite, and the reason is recorded in the report that came with this rewrite rather than papered
 *     over with a test that passes by asserting nothing.
 *
 * ## Fake `claude` binary (wired automatically)
 * The IDE under test points at a deterministic stand-in instead of the real `claude` binary:
 * `runIdeForUiTests` passes `-Dclaudejb.fakeClaude=<bin/fake-claude>` and `-Dclaudejb.fakeFixture=<a JSONL
 * fixture>`, which `ClaudeSettings`/`SettingsLaunchEnv` read only when present (a no-op in a shipped IDE).
 *
 * ## Style rules for the JS in this suite
 * [js] hands the expression to `JCefBrowserFixture.executeJsInBrowser`, which embeds it in a **single-quoted,
 * single-line** Nashorn string on the IDE side. So an expression must be **one line** and must contain **no
 * single quotes**; [js] enforces both rather than letting a bad snippet come back as a confusing timeout.
 * Write `(function () { … })()` one-liners with double quotes, returning a string.
 */
abstract class UiTestBase {

    protected val remoteRobot: RemoteRobot = RemoteRobot(robotServerUrl())

    /** Generous default; CI under Xvfb is slow to paint and the browser handshake adds latency. */
    protected val longTimeout: Duration = Duration.ofSeconds(60)
    protected val shortTimeout: Duration = Duration.ofSeconds(10)

    /** Cached per test instance: one fixture per browser, as [JCefBrowserFixture]'s own docs ask. */
    private var cachedWeb: JCefBrowserFixture? = null

    // ── Swing layer ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens (or focuses) the "Claude Code" tool window, then returns the plugin's chat strip so callers can
     * scope further lookups to it.
     *
     * Idempotent: the tool window auto-opens on startup (restore-or-create), so it only clicks the stripe
     * button when the strip is not in the tree yet — clicking it while open would HIDE the window.
     */
    protected fun openClaudeToolWindow(): CommonContainerFixture {
        if (remoteRobot.findAll<ComponentFixture>(CHAT_TABS).isEmpty()) {
            remoteRobot.find<ComponentFixture>(
                byXpath("//div[@class='SquareStripeButton' and @accessiblename='Claude Code']"),
                shortTimeout,
            ).click()
        }
        return chatTabs()
    }

    /**
     * The plugin's chat strip ([ChatTabsPanel]) — anchored on the component's own FQN rather than on the
     * tool-window decorator, whose class name is platform-internal and has changed between releases.
     */
    protected fun chatTabs(): CommonContainerFixture =
        remoteRobot.find(CommonContainerFixture::class.java, CHAT_TABS, longTimeout)

    /**
     * Clicks a tool-window title action by its action text ("New Chat", "Interrupt", "Diff History", …).
     *
     * `ActionButton` exposes the text as its accessible name and, with the shortcut appended, as its tooltip —
     * hence the OR, so a keymap that binds one of them does not break the locator.
     *
     * The tool window carries six title actions and is anchored on the right, so on a narrow window the
     * platform folds the tail of them into the header's `⋮`. The fallback is not defensive noise: without it
     * this suite would pass or fail on how wide the user last left the tool window.
     */
    protected fun clickTitleAction(text: String) {
        val direct = remoteRobot.findAll<ComponentFixture>(
            byXpath("//div[@class='ActionButton' and (@accessiblename='$text' or contains(@tooltiptext,'$text'))]"),
        )
        if (direct.isNotEmpty()) {
            direct.first().click()
            return
        }
        clickHeaderButton(OVERFLOW_NAMES)
        clickMenuItem(text)
    }

    /** Opens the tool window's gear (`setAdditionalGearActions`) menu. */
    protected fun openGearMenu() = clickHeaderButton(GEAR_NAMES)

    /**
     * Clicks the first tool-window header button whose accessible name or tooltip matches one of [names].
     *
     * An OR-set rather than one name because the header's own controls have been renamed across platform
     * versions ("Show Options Menu" / "Options" / "More"), and a UI suite that only runs nightly must not
     * start failing on a cosmetic rename it can absorb.
     */
    private fun clickHeaderButton(names: List<String>) {
        val predicate = names.joinToString(" or ") { "@accessiblename='$it' or contains(@tooltiptext,'$it')" }
        remoteRobot.find<ComponentFixture>(
            byXpath("//div[@class='ActionButton' and ($predicate)]"),
            shortTimeout,
        ).click()
    }

    /**
     * Clicks an item of the currently open IDE popup by the **start** of its painted label.
     *
     * Action popups are a `JBList` inside a `HeavyWeightWindow`: the rows are not components, so they are
     * located through the painted-text extractor rather than by XPath.
     *
     * Matching on a prefix rather than on "contains" is not fussiness — the gear menu has both "Settings…"
     * and "Effective Settings…", and a contains-match for the first would click the second, silently, because
     * it comes higher up the list. A prefix also survives the trailing ellipsis being painted as `…` or `...`.
     */
    protected fun clickMenuItem(text: String) {
        val popup = remoteRobot.find<ComponentFixture>(byXpath("//div[@class='HeavyWeightWindow']"), shortTimeout)
        waitFor(shortTimeout, POLL, "the popup item '$text'", "no popup item starting with '$text'") {
            runCatching { popup.findAllText().any { it.text.trim().startsWith(text) } }.getOrDefault(false)
        }
        popup.findAllText().first { it.text.trim().startsWith(text) }.click()
    }

    // ── Web layer ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The browser of the chat currently on screen.
     *
     * Resolved from the *showing* browser component, not simply the first match: every chat's browser stays
     * in the hierarchy for the life of its tab (they are cards of a `CardLayout`), so a naive `find` can hand
     * back a page nobody is looking at and every assertion afterwards would be about the wrong chat.
     *
     * @param refresh drop the cached fixture — mandatory after anything that swaps the visible chat.
     */
    protected fun web(refresh: Boolean = false): JCefBrowserFixture {
        if (refresh) cachedWeb = null
        cachedWeb?.let { return it }
        val candidates = chatTabs().findAll<ComponentFixture>(WEB_VIEW)
        check(candidates.isNotEmpty()) { "no JCEF browser under the chat strip — did the chat tab fail to build?" }
        val showing = candidates.firstOrNull { runCatching { it.isShowing }.getOrDefault(false) } ?: candidates.first()
        return JCefBrowserFixture(remoteRobot, showing.remoteComponent).also { cachedWeb = it }
    }

    /**
     * Evaluates a one-line JavaScript expression in the chat page and returns its value as a string.
     *
     * See the class KDoc for why the expression must be one line and free of single quotes.
     */
    protected fun js(expression: String): String {
        require(!expression.contains('\n')) { "the browser bridge takes a ONE-LINE expression: $expression" }
        require(!expression.contains('\'')) { "use double quotes — single quotes break the bridge: $expression" }
        // A backslash would be consumed by the Nashorn string this travels in, so an escaped quote arrives
        // unescaped and the page gets a syntax error instead of an answer. Rejecting it here turns a
        // baffling timeout into a message that names the cause.
        require(!expression.contains('\\')) { "no backslash escapes survive the bridge: $expression" }
        return web().executeJsInBrowser(expression)
    }

    /** [js] for an expression that yields a boolean. */
    protected fun jsBool(expression: String): Boolean = js(expression) == "true"

    /** [js] for an expression that yields a number. */
    protected fun jsInt(expression: String): Int = js(expression).trim().toDouble().toInt()

    /** Waits until a boolean DOM expression holds, failing with [message] when it never does. */
    protected fun waitForWeb(message: String, expression: String) {
        waitFor(longTimeout, POLL, message, message) {
            runCatching { jsBool(expression) }.getOrDefault(false)
        }
    }

    /**
     * A DOM element, for clicking it where it actually is on screen.
     *
     * **Quote the xpath with single quotes.** The fixture escapes `'` to `\x27` on the way through, which is
     * exactly what survives the Nashorn string it travels in and arrives at the page as a quote again; a
     * double quote is escaped the same way and then lands *inside* the double-quoted JS call that carries it,
     * breaking the expression. The rule is the mirror image of the one for [js], which must use double
     * quotes.
     */
    protected fun findDom(xpath: String) = web().findElement(xpath, shortTimeout)

    /**
     * Waits until the chat's web app has booted: the shell is parsed and the modules have registered their
     * halves of the `window.cc` API (the tab bar's is the last of them, so it is the honest "all up" signal).
     */
    protected fun awaitChatPage() {
        waitForWeb(
            "the chat page to boot (window.cc.tabs registered)",
            "(function () { return String(!!(document.getElementById(\"app\") && window.cc && " +
                "typeof window.cc.tabs === \"function\" && typeof window.cc.session === \"function\")); })()",
        )
    }

    // ── Product-specific helpers ─────────────────────────────────────────────────────────────────────────

    /** How many chat pills the tab bar is drawing (the first row of the bar — the chats). */
    protected fun chatPillCount(): Int =
        jsInt(
            "(function () { var r = document.querySelector(\"#tabsbar .tab-rows .tab-row\"); " +
                "return String(r ? r.querySelectorAll(\".tab-capsule .pill\").length : 0); })()",
        )

    /**
     * Clicks "New Chat" and leaves the harness pointing at the chat that opened.
     *
     * **The order of the two waits is the whole method.** A click returns as soon as the event is posted, so
     * looking for the new browser straight away can still find the old one showing and cache it — every later
     * assertion would then be made against a page nobody is looking at, and would pass, because both pages
     * draw the same bar. So the wait happens FIRST, on the page we already have: `ClaudeToolWindowFactory`
     * adds the tab, selects it (which is what swaps the `CardLayout` card) and pushes the new chat list in one
     * EDT event, so a page that reports the extra pill is proof that the swap has already happened. Only then
     * is the fixture re-resolved.
     */
    protected fun newChat() {
        val before = chatPillCount()
        clickTitleAction("New Chat")
        waitForWeb(
            "the tab bar to show ${before + 1} chats",
            "(function () { var r = document.querySelector(\"#tabsbar .tab-rows .tab-row\"); " +
                "return String(!!r && r.querySelectorAll(\".tab-capsule .pill\").length >= ${before + 1}); })()",
        )
        web(refresh = true)
        awaitChatPage()
    }

    /** Puts the caret in the composer by clicking the real textarea where it is painted. */
    protected fun focusComposer() {
        waitForWeb(
            "the composer to be built",
            "(function () { return String(!!document.querySelector(\"textarea.composer-input\")); })()",
        )
        findDom("//textarea[contains(@class,'composer-input')]").clickAtCenter()
    }

    /** What is in the composer right now. */
    protected fun composerText(): String =
        js(
            "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
                "return t ? t.value : \"\"; })()",
        )

    /** Types into whatever has the keyboard focus (use [focusComposer] first). */
    protected fun type(text: String) = remoteRobot.keyboard { enterText(text) }

    /**
     * Opens the sandbox project's `src/Sample.kt` in an editor.
     *
     * A fixture step, not an assertion: the editor-context actions ("Add Current File as @-context") have
     * nothing to work with unless a file is open, and there is no stable, fast Swing route to opening one
     * (the Project view needs expanding, Search Everywhere needs indexing to have settled). Platform API
     * only — no plugin class is touched from here, so this cannot go stale with a refactor of ours.
     */
    protected fun openSampleFile() {
        remoteRobot.runJs(
            """
            const projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            if (projects.length > 0) {
                const project = projects[0];
                const fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance();
                const file = fs.findFileByPath(project.getBasePath() + "/src/Sample.kt");
                if (file !== null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true);
                }
            }
            """.trimIndent(),
            true,
        )
    }

    companion object {
        /** Poll interval for every wait in the suite. */
        val POLL: Duration = Duration.ofMillis(500)

        /** The plugin's chat strip — one per tool window, and the only Swing component the chat UI still has. */
        val CHAT_TABS: Locator = byXpath("//div[@javaclass='dev.lain.claudejb.ui.ChatTabsPanel']")

        /**
         * The embedded browser's component: `JBCefOsrComponent` when JCEF renders off-screen, a heavyweight
         * `Canvas` when it does not. Same OR-set JetBrains' own `CommonContainerFixture.browser()` uses.
         */
        val WEB_VIEW: Locator = byXpath("//div[contains(@class,'JBCef') or contains(@class,'Canvas')]")

        /** What the tool-window header's gear has been called across platform versions. */
        val GEAR_NAMES: List<String> = listOf("Show Options Menu", "Options")

        /** …and its overflow button, which swallows title actions when the window is narrow. */
        val OVERFLOW_NAMES: List<String> = listOf("More", "Show More")

        /** Robot-server endpoint; override via `-Drobot-server.url` (e.g. a remote runner). */
        fun robotServerUrl(): String = System.getProperty("robot-server.url") ?: "http://127.0.0.1:8082"
    }
}
