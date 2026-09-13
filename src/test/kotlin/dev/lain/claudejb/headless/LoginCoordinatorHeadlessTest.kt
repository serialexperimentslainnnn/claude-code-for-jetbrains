package dev.lain.claudejb.headless

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.controller.session.SessionNotifier
import dev.lain.claudejb.controller.session.auth.LoginAttempt
import dev.lain.claudejb.controller.session.auth.LoginCoordinator
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.model.settings.SecretStore
import dev.lain.claudejb.model.settings.SettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files

class LoginCoordinatorHeadlessTest : BasePlatformTestCase() {

    private class ScriptedPty : Process() {
        private val feed = PipedOutputStream()
        private val out = PipedInputStream(feed, 1 shl 16)
        val typed = ByteArrayOutputStream()

        @Volatile var exit = 0
        override fun getOutputStream(): OutputStream = typed
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exit
        override fun exitValue(): Int = exit
        override fun destroy() = runCatching { feed.close() }.let { }
        fun say(text: String) = feed.write(text.toByteArray()).also { feed.flush() }
        fun end(code: Int) {
            exit = code
            feed.close()
        }
    }

    private class RecordingUi : LoginCoordinator.LoginUi {
        val urls = ArrayList<String>()
        var codeRequests = 0
        val results = ArrayList<Pair<Boolean, String>>()
        override fun onAuthUrl(url: String) {
            urls += url
        }
        override fun onCodeRequested() {
            codeRequests++
        }
        override fun onLoginResult(success: Boolean, message: String) {
            results += success to message
        }
    }

    private lateinit var home: File
    private val settings get() = ClaudeSettings.getInstance(project)
    private val ptys = ArrayList<ScriptedPty>()
    private var restarts = 0
    private val ui = RecordingUi()

    private val coordinator by lazy {
        LoginCoordinator(
            project,
            { ApplicationManager.getApplication().invokeLater(it) },
            SessionNotifier(project),
            { restarts++ },
        ) { binary, mode, loginUi, host ->
            LoginAttempt(project, { ApplicationManager.getApplication().invokeLater(it) }, binary, mode, loginUi, host) { _, _, _ ->
                ScriptedPty().also { ptys += it }
            }
        }
    }

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claudejb-home").toFile()
        CredentialsVault.homeOverride = home
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load(settings.scope)
        settings.replaceState(ClaudeSettings.State())
        settings.state.claudePath = System.getProperty("claudejb.fakeClaude") ?: error("claudejb.fakeClaude is not set")
        coordinator.attachUi(ui)
    }

    override fun tearDown() {
        try {
            coordinator.cancelLogin()
            SecretStore.storeOverride = null
            CredentialsVault.homeOverride = null
            home.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("timed out waiting for: $message", condition())
    }

    private val liveOutput =
        "Opening browser to sign in\r\n\r\nhttps://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz\r\n\r\n" +
            "Paste code here if prompted > "

    fun `test the card flow - url and code prompt reach the ui, the code reaches the pty, and a verified login restarts the chat`() {
        coordinator.start()
        waitUntil("the pty to start") { ptys.size == 1 && coordinator.inProgress }
        coordinator.start()
        assertEquals("a second start while signing in must not spawn another pty", 1, ptys.size)
        ptys.single().say(liveOutput)
        waitUntil("the ui to get the url and the prompt") { ui.urls.size == 1 && ui.codeRequests == 1 }
        assertTrue(ui.urls.single().startsWith("https://claude.com/cai/oauth/authorize"))
        coordinator.submitCode(" code-123 ")
        assertEquals("code-123\r", ptys.single().typed.toString())
        ptys.single().say("Login successful. Press Enter to continue")
        ptys.single().end(0)
        waitUntil("the login to be verified and the chat restarted") { ui.results.size == 1 && restarts == 1 }
        assertTrue(ui.results.single().first)
        assertTrue("the ui gets the binary's own wording", ui.results.single().second.endsWith("Login successful."))
        assertFalse(coordinator.inProgress)
        assertFalse(settings.signedOut)
    }

    fun `test a failed login reaches the ui with the binary's wording and restarts nothing`() {
        coordinator.start(LoginCoordinator.Mode.CONSOLE)
        waitUntil("the pty to start") { ptys.size == 1 }
        ptys.single().say("Invalid code, please try again.")
        ptys.single().end(1)
        waitUntil("the failure to reach the ui") { ui.results.size == 1 }
        assertEquals(false to "Invalid code, please try again.", ui.results.single())
        assertEquals(0, restarts)
        assertFalse(coordinator.inProgress)
    }

    fun `test cancelling forgets the attempt, and a code without one goes nowhere`() {
        coordinator.submitCode("nothing")
        coordinator.start(LoginCoordinator.Mode.SSO)
        waitUntil("the pty to start") { ptys.size == 1 && coordinator.inProgress }
        coordinator.cancelLogin()
        assertFalse(coordinator.inProgress)
        coordinator.submitCode("late")
        assertEquals("", ptys.single().typed.toString())
    }

    fun `test another provider is told to use its key, and the prompt to sign in is shown once until a clean result`() {
        settings.state.provider = Provider.DEEPSEEK.id
        coordinator.start()
        assertTrue(ptys.isEmpty())
        coordinator.maybePrompt()
        settings.state.provider = Provider.ANTHROPIC.id
        coordinator.maybePrompt()
        coordinator.maybePrompt()
        coordinator.onCleanResult()
        coordinator.maybePrompt()
        assertTrue(ptys.isEmpty())
        coordinator.detachUi(ui)
    }
}
