package dev.lain.claudejb.controller.session.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.process.ClaudeBinaryLocator
import dev.lain.claudejb.controller.process.TerminalLauncher
import dev.lain.claudejb.controller.session.SessionNotifier
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.util.thisLogger
import java.io.File

class LoginCoordinator(
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val notifier: SessionNotifier,
    private val restartSession: () -> Unit,
    private val attempts: (File, Mode, LoginUi, LoginAttempt.Host) -> LoginAttempt = { binary, mode, ui, host ->
        LoginAttempt(project, edt, binary, mode, ui, host)
    },
) {

    private val log = thisLogger()

    interface LoginUi {
        fun onAuthUrl(url: String)

        fun onCodeRequested()

        fun onLoginResult(success: Boolean, message: String)
    }

    enum class Mode(val args: List<String>) {
        SUBSCRIPTION(listOf("auth", "login")),
        CONSOLE(listOf("auth", "login", "--console")),
        SSO(listOf("auth", "login", "--sso")),
    }

    private val completion = SignInCompletion(project, edt)

    @Volatile private var ui: LoginUi? = null

    @Volatile private var prompted = false

    @Volatile private var attempt: LoginAttempt? = null

    @Volatile private var signingIn = false

    private var verifyTimer: javax.swing.Timer? = null

    val inProgress: Boolean get() = signingIn

    fun attachUi(loginUi: LoginUi) {
        ui = loginUi
    }

    fun detachUi(loginUi: LoginUi) {
        if (ui === loginUi) ui = null
    }

    fun submitCode(code: String) {
        val current = attempt ?: return
        current.submitCode(code)
        verifyTimer?.stop()
        verifyTimer = javax.swing.Timer(VERIFY_TIMEOUT_MS) {
            if (attempt === current) {
                cancelLogin()
                ui?.onLoginResult(false, "No answer after submitting the code. Try again — or use the API-key route.")
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun cancelLogin() {
        verifyTimer?.stop()
        verifyTimer = null
        attempt?.cancel()
        attempt = null
        signingIn = false
    }

    fun onCleanResult() {
        prompted = false
    }

    fun maybePrompt() {
        if (ClaudeSettings.getInstance(project).provider != Provider.ANTHROPIC) return
        if (prompted) return
        prompted = true
        notifier.warning("You don't seem to be logged in. Sign in to Claude to continue.", "Sign in") { start() }
    }

    fun start(mode: Mode = Mode.SUBSCRIPTION) {
        val settings = ClaudeSettings.getInstance(project)
        if (settings.provider != Provider.ANTHROPIC) {
            notifier.info(
                "Sign-in is only for the Anthropic provider. You're on ${settings.provider.label} — " +
                    "set its API key in Settings instead.",
            )
            return
        }
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            notifier.missingBinary()
            return
        }
        val cardUi = ui
        if (cardUi != null) {
            beginPty(binary, mode, cardUi) { if (!openTerminal(binary, mode)) manualFallback(binary, mode) }
            return
        }
        edt {
            if (openTerminal(binary, mode)) return@edt
            log.info("IDE terminal unavailable for /login — falling back to the dialog-driven PTY flow")
            val dialogUi = DialogLoginUi(project, notifier, ::submitCode, ::cancelLogin)
            beginPty(binary, mode, dialogUi, onStarted = {
                notifier.info("Signing in… your browser should open. Approve access there to finish.")
            }) { manualFallback(binary, mode) }
        }
    }

    private fun beginPty(binary: File, mode: Mode, loginUi: LoginUi, onStarted: () -> Unit = {}, fallback: () -> Unit) {
        if (signingIn) return
        signingIn = true
        val next = attempts(binary, mode, loginUi, host)
        ApplicationManager.getApplication().executeOnPooledThread {
            val started = next.start()
            edt {
                when {
                    !signingIn -> next.cancel()

                    started -> {
                        attempt = next
                        onStarted()
                    }

                    else -> {
                        signingIn = false
                        fallback()
                    }
                }
            }
        }
    }

    private val host = object : LoginAttempt.Host {
        override fun ptyEnded() {
            verifyTimer?.stop()
            verifyTimer = null
            attempt = null
        }

        override fun finished(verified: Boolean) {
            signingIn = false
            if (verified) restartSession()
        }

        override fun complete(binary: File, done: (Boolean, String) -> Unit) = completion.complete(binary, done)

        override fun announce(text: String) = notifier.info(text)
    }

    private fun openTerminal(binary: File, mode: Mode): Boolean {
        val opened = TerminalLauncher.openAndRunCommand(project, listOf(binary.absolutePath) + mode.args, "claude login")
        if (opened) {
            notifier.info(
                "Finish signing in in the terminal — the browser opens automatically. " +
                    "When it confirms you're logged in, restart the chat to use it.",
                "Restart chat",
            ) { restartSession() }
        }
        return opened
    }

    private fun manualFallback(binary: File, mode: Mode) {
        notifier.error(
            "Couldn't start the sign-in flow. Run this in a terminal, then restart the chat:\n" +
                TerminalLauncher.loginCommand(binary.absolutePath, mode.args),
        )
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 45_000
    }
}
