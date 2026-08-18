package dev.lain.claudejb.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JCefBrowserFixture
import com.intellij.remoterobot.search.locators.Locator
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
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
 * ### Two preconditions the IDE under test must meet — `runIdeForUiTests` supplies both
 *  1. **`-Dide.browser.jcef.jsQueryPoolSize=10000`** on its command line (a documented requirement of
 *     [JCefBrowserFixture]: creating a JS query against an already-loaded browser needs pre-reserved callback
 *     slots). Without it every DOM-level test fails at fixture construction — loudly, with "Can't find cef
 *     browser" or an IllegalStateException, never silently green.
 *  2. **An identity.** `ClaudeSession.start()` refuses to launch without a credential, and `AuthGate
 *     .hasCredential` falls through to the binary's own `auth status` when the IDE password safe holds none.
 *     `bin/fake-claude` answers that probe, so a machine with an empty safe still gets a session rather than
 *     the sign-in card. An IDE launched without the stand-in wired in has neither, and shows the card.
 *
 * No test here may assert something that is equally true of a chat that never started: **a test that passes by
 * asserting nothing is the one failure a UI suite cannot detect in itself.**
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

    /**
     * How many chats the tab bar was drawing before [newChat] first ran in this test, and `null` while it has
     * not run at all. It is the count [closeChatsOpenedHere] gives the IDE back to — recorded rather than
     * assumed to be one, because the IDE under test restores whatever tabs the previous run left open.
     */
    private var chatsBeforeThisTest: Int? = null

    /**
     * Fails the test at once when the sandbox project is not a git repository of its own.
     *
     * `runIdeForUiTests` opens [SANDBOX] as the IDE's project, and git resolves its working tree by walking
     * **up** from wherever a command runs. With no `.git` of its own that walk does not stop at the fixture: it
     * reaches **this** repository. So a test driving one of the Git surfaces — the half of 5.5.0 with the most
     * to gain from an end-to-end test, and therefore the one somebody writes next — would run `git restore`,
     * `git checkout` or `git clean` against the plugin's own working tree. Nothing asks twice, and an
     * uncommitted change has nothing underneath it to come back from.
     *
     * **It refuses; it does not repair.** A harness that writes into the tree so that it can run is the same
     * defect wearing the fix's clothes, and which repository the fixture gets is a decision about what this one
     * tracks — not something a test may take on its author's behalf.
     *
     * **It asserts rather than skips**, for the reason the `-PuiTest.enabled` gate in `build.gradle.kts`
     * already gives: a skip is `BUILD SUCCESSFUL` with zero tests executed, the one outcome a verification task
     * must never produce.
     *
     * It reads the disk of the machine running these tests, which is the machine running the IDE unless
     * `-Drobot-server.url` points somewhere else.
     */
    @BeforeEach
    fun requireSandboxIsItsOwnGitRepo() {
        if (File(SANDBOX, ".git").exists()) return
        val path = SANDBOX.absolutePath
        throw IllegalStateException(
            "the UI sandbox project is not a git repository of its own: $path has no .git, so git run there " +
                "walks up into THIS repository and a Git action driven from a test would hit the plugin's own " +
                "working tree instead of a fixture. Give the fixture its own history — `git init -b main " +
                "$path && git -C $path add -A && git -C $path commit -m \"ui sandbox fixture\"` — which leaves " +
                "this repository tracking those files exactly as it does today.",
        )
    }

    /**
     * Fails the test at once, naming the endpoint, when nothing answers there.
     *
     * Without it the first symptom of an IDE that never started is a wait expiring after [longTimeout] with a
     * message about the page — a connection failure reported as a product failure, once per test, at the cost
     * of the suite's whole runtime. The probe is the same component search the suite makes anyway, so an IDE
     * that is up but has not built the chat strip yet passes it: what is checked here is the socket, not the UI.
     */
    @BeforeEach
    fun requireRobotServer() {
        runCatching { remoteRobot.findAll<ComponentFixture>(CHAT_TABS) }.onFailure { cause ->
            throw IllegalStateException(
                "no robot-server answering at ${robotServerUrl()} — start the IDE under test with " +
                    "`./gradlew runIdeForUiTests`, or point the suite at another one with -Drobot-server.url. " +
                    "Cause: ${cause.message}",
                cause,
            )
        }
    }

    /**
     * Gives back the chats [newChat] opened, so the IDE under test ends the test where it started.
     *
     * **A chat owns a JCEF browser for as long as its tab exists**, and nothing in the product closes a tab by
     * itself. The IDE stays up for the whole suite — it is booted once, by `runIdeForUiTests`, and the nightly
     * runner keeps it — so a chat opened and never closed is a live Chromium for the rest of the run, and the
     * two tests that open chats adaptively can open ten apiece. Closing here rather than in each test is the
     * same rule the rest of this class follows: [newChat] is the only thing that opens one, and it lives here.
     *
     * **Why the first chat is selected first.** The close travels as a `closeChat` bridge message from the page
     * that issues it, and the chat [newChat] leaves selected is one of the ones being closed. Every page draws
     * the whole bar, so the first chat's page can close every later one while staying alive to answer for it.
     * The selection is waited for on the page we already have — for the reason spelled out in [newChat] — and
     * only then is the fixture re-resolved.
     */
    @AfterEach
    fun closeChatsOpenedHere() {
        val baseline = chatsBeforeThisTest ?: return
        chatsBeforeThisTest = null

        js(SELECT_FIRST_CHAT)
        waitForWeb("the first chat to take the selection back", FIRST_CHAT_IS_CURRENT)
        web(refresh = true)
        awaitChatPage()

        js(closeChatsAfter(baseline))
        waitForWeb("the chats this test opened to close", chatCountIsAtMost(baseline))
        cachedWeb = null
    }

    // ── Swing layer ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens (or focuses) the "Claude Code" tool window, then returns the plugin's chat strip so callers can
     * scope further lookups to it.
     *
     * **Idempotent by construction, not by a prior check.** `ToolWindow.activate` shows a hidden window and
     * re-focuses a visible one; it never hides. The stripe button is a TOGGLE, so driving it needs a decision
     * about whether the window is already open — and the only evidence RemoteRobot has for that is whether the
     * strip is in the component tree, which is a different fact: a window that is open but has not built its
     * content yet is indistinguishable from a closed one, and acting on that reading closes the window the
     * test is about to use. Asking the platform removes the question rather than answering it better.
     *
     * The tool-window id is the one `plugin.xml` registers. Platform API only — no plugin class is named here,
     * so this cannot go stale with a refactor of ours.
     */
    protected fun openClaudeToolWindow(): CommonContainerFixture {
        remoteRobot.runJs(
            """
            const projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            if (projects.length > 0) {
                const manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(projects[0]);
                const claude = manager.getToolWindow("Claude Code");
                if (claude !== null) {
                    claude.activate(null);
                }
            }
            """.trimIndent(),
            true,
        )
        return chatTabs()
    }

    /**
     * The plugin's chat strip ([ChatTabsPanel]) — anchored on the component's own FQN rather than on the
     * tool-window decorator, whose class name is platform-internal and has changed between releases.
     */
    protected fun chatTabs(): CommonContainerFixture =
        remoteRobot.find(CommonContainerFixture::class.java, CHAT_TABS, longTimeout)

    /**
     * Clicks a tool-window title action by its action text ("New Chat", "Interrupt", "Commands", …).
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
        if (chatsBeforeThisTest == null) chatsBeforeThisTest = before
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

        /**
         * The fixture project the IDE under test has open — the same path `build.gradle.kts` hands
         * `runIdeForUiTests` as the IDE's first positional argument.
         *
         * Relative on purpose: a Gradle `Test` task runs with the project directory as its working directory,
         * and resolving it here rather than from a system property keeps the path in one place. The failure in
         * [requireSandboxIsItsOwnGitRepo] names the absolute path it looked at, so a working directory that is
         * not the one assumed here says so instead of reading as a missing repository.
         */
        private val SANDBOX = File("src/uiTest/resources/sandbox-project")

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

        /**
         * The chat pills of the tab bar's first row, in bar order.
         *
         * A tab is a `.pill-wrap` holding SIBLINGS — the chat's own `<button class="pill">` and then its
         * glyph controls — so `.pill` is the chat's button and not the whole tab. It stays the anchor here
         * because it is the button that carries what this harness asks of a tab: one per chat to count,
         * `click()` to select, and `aria-current` to read. Anything that lives on a SIBLING is reached
         * through the wrapper (see [closeChatsAfter]); reaching under `.pill` for one finds nothing.
         */
        private const val PILLS = "document.querySelectorAll(\"#tabsbar .tab-rows .tab-row .tab-capsule .pill\")"

        /** Selects the leftmost chat — the one [closeChatsOpenedHere] then closes the others from. */
        private const val SELECT_FIRST_CHAT =
            "(function () { var p = $PILLS; if (p.length) { p[0].click(); } return String(p.length); })()"

        private const val FIRST_CHAT_IS_CURRENT =
            "(function () { var p = $PILLS; " +
                "return String(p.length > 0 && p[0].getAttribute(\"aria-current\") === \"true\"); })()"

        /**
         * Presses the ✕ of every chat past [keep], back to front — the product's own close control, so this
         * exercises the same `closeChat` round trip a user does instead of a private back door.
         *
         * The handlers capture their chat's id, so a pill detached by a repaint mid-pass still asks the host
         * to close the right chat.
         *
         * The × is a SIBLING of the pill, not a child of it — it is a `<button>`, and interactive content
         * inside a `<button>` is what the content model forbids and what made this control dead on screen.
         * So the walk goes up to the tab (`.pill-wrap`) and back down. Asking `p[i]` for it directly returns
         * null, and a null here is a silent no-op: nothing closes, and the failure surfaces two waits later
         * as "still too many chats" rather than as a bad selector.
         */
        private fun closeChatsAfter(keep: Int) =
            "(function () { var p = $PILLS; for (var i = p.length - 1; i >= $keep; i--) { " +
                "var w = p[i].closest(\".pill-wrap\"); var x = w && w.querySelector(\".pill-x\"); " +
                "if (x) { x.click(); } } return String(p.length); })()"

        private fun chatCountIsAtMost(keep: Int) =
            "(function () { var p = $PILLS; return String(p.length <= $keep); })()"
    }
}
