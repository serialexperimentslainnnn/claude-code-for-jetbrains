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

abstract class UiTestBase {

    protected val remoteRobot: RemoteRobot = RemoteRobot(robotServerUrl())

    protected val longTimeout: Duration = Duration.ofSeconds(60)
    protected val shortTimeout: Duration = Duration.ofSeconds(10)

    private var cachedWeb: JCefBrowserFixture? = null

    private var chatsBeforeThisTest: Int? = null

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

    protected fun chatTabs(): CommonContainerFixture =
        remoteRobot.find(CommonContainerFixture::class.java, CHAT_TABS, longTimeout)

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

    protected fun openGearMenu() = clickHeaderButton(GEAR_NAMES)

    private fun clickHeaderButton(names: List<String>) {
        val predicate = names.joinToString(" or ") { "@accessiblename='$it' or contains(@tooltiptext,'$it')" }
        remoteRobot.find<ComponentFixture>(
            byXpath("//div[@class='ActionButton' and ($predicate)]"),
            shortTimeout,
        ).click()
    }

    protected fun clickMenuItem(text: String) {
        val popup = remoteRobot.find<ComponentFixture>(byXpath("//div[@class='HeavyWeightWindow']"), shortTimeout)
        waitFor(shortTimeout, POLL, "the popup item '$text'", "no popup item starting with '$text'") {
            runCatching { popup.findAllText().any { it.text.trim().startsWith(text) } }.getOrDefault(false)
        }
        popup.findAllText().first { it.text.trim().startsWith(text) }.click()
    }

    protected fun web(refresh: Boolean = false): JCefBrowserFixture {
        if (refresh) cachedWeb = null
        cachedWeb?.let { return it }
        val candidates = chatTabs().findAll<ComponentFixture>(WEB_VIEW)
        check(candidates.isNotEmpty()) { "no JCEF browser under the chat strip — did the chat tab fail to build?" }
        val showing = candidates.firstOrNull { runCatching { it.isShowing }.getOrDefault(false) } ?: candidates.first()
        return JCefBrowserFixture(remoteRobot, showing.remoteComponent).also { cachedWeb = it }
    }

    protected fun js(expression: String): String {
        require(!expression.contains('\n')) { "the browser bridge takes a ONE-LINE expression: $expression" }
        require(!expression.contains('\'')) { "use double quotes — single quotes break the bridge: $expression" }
        require(!expression.contains('\\')) { "no backslash escapes survive the bridge: $expression" }
        return web().executeJsInBrowser(expression)
    }

    protected fun jsBool(expression: String): Boolean = js(expression) == "true"

    protected fun jsInt(expression: String): Int = js(expression).trim().toDouble().toInt()

    protected fun waitForWeb(message: String, expression: String) {
        waitFor(longTimeout, POLL, message, message) {
            runCatching { jsBool(expression) }.getOrDefault(false)
        }
    }

    protected fun findDom(xpath: String) = web().findElement(xpath, shortTimeout)

    protected fun awaitChatPage() {
        waitForWeb(
            "the chat page to boot (window.cc.tabs registered)",
            "(function () { return String(!!(document.getElementById(\"app\") && window.cc && " +
                "typeof window.cc.tabs === \"function\" && typeof window.cc.session === \"function\")); })()",
        )
    }

    protected fun chatPillCount(): Int =
        jsInt(
            "(function () { var r = document.querySelector(\"#tabsbar .tab-rows .tab-row\"); " +
                "return String(r ? r.querySelectorAll(\".tab-capsule .pill\").length : 0); })()",
        )

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

    protected fun focusComposer() {
        waitForWeb(
            "the composer to be built",
            "(function () { return String(!!document.querySelector(\"textarea.composer-input\")); })()",
        )
        findDom("//textarea[contains(@class,'composer-input')]").clickAtCenter()
    }

    protected fun composerText(): String =
        js(
            "(function () { var t = document.querySelector(\"textarea.composer-input\"); " +
                "return t ? t.value : \"\"; })()",
        )

    protected fun type(text: String) = remoteRobot.keyboard { enterText(text) }

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
        val POLL: Duration = Duration.ofMillis(500)

        private val SANDBOX = File("src/uiTest/resources/sandbox-project")

        val CHAT_TABS: Locator = byXpath("//div[@javaclass='dev.lain.claudejb.ui.ChatTabsPanel']")

        val WEB_VIEW: Locator = byXpath("//div[contains(@class,'JBCef') or contains(@class,'Canvas')]")

        val GEAR_NAMES: List<String> = listOf("Show Options Menu", "Options")

        val OVERFLOW_NAMES: List<String> = listOf("More", "Show More")

        fun robotServerUrl(): String = System.getProperty("robot-server.url") ?: "http://127.0.0.1:8082"

        private const val PILLS = "document.querySelectorAll(\"#tabsbar .tab-rows .tab-row .tab-capsule .pill\")"

        private const val SELECT_FIRST_CHAT =
            "(function () { var p = $PILLS; if (p.length) { p[0].click(); } return String(p.length); })()"

        private const val FIRST_CHAT_IS_CURRENT =
            "(function () { var p = $PILLS; " +
                "return String(p.length > 0 && p[0].getAttribute(\"aria-current\") === \"true\"); })()"

        private fun closeChatsAfter(keep: Int) =
            "(function () { var p = $PILLS; for (var i = p.length - 1; i >= $keep; i--) { " +
                "var w = p[i].closest(\".pill-wrap\"); var x = w && w.querySelector(\".pill-x\"); " +
                "if (x) { x.click(); } } return String(p.length); })()"

        private fun chatCountIsAtMost(keep: Int) =
            "(function () { var p = $PILLS; return String(p.length <= $keep); })()"
    }
}
