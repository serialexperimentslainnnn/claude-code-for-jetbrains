package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.ClaudeLoginFlow
import dev.lain.claudejb.process.TerminalLauncher
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.resolveEnv
import java.io.File

class LoginCoordinator(
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val notifyInfo: (String) -> Unit,
    private val notifyError: (String) -> Unit,
    private val notifyMissingBinary: () -> Unit,
    private val restartSession: () -> Unit,
) {

    private val log = thisLogger()

    interface LoginUi {
        fun onAuthUrl(url: String)

        fun onCodeRequested()

        fun onLoginResult(success: Boolean, message: String)
    }

    @Volatile private var ui: LoginUi? = null

    fun attachUi(loginUi: LoginUi) {
        ui = loginUi
    }

    fun detachUi(loginUi: LoginUi) {
        if (ui === loginUi) ui = null
    }

    @Volatile private var prompted = false

    @Volatile private var flow: ClaudeLoginFlow? = null

    @Volatile private var authUrl: String? = null

    val inProgress: Boolean get() = signingIn

    @Volatile private var signingIn = false

    @Volatile private var loginMode = Mode.SUBSCRIPTION

    fun submitCode(code: String) {
        val current = flow ?: return
        current.submitCode(code)
        verifyTimer?.stop()
        verifyTimer = javax.swing.Timer(VERIFY_TIMEOUT_MS) {
            if (flow === current) {
                cancelLogin()
                ui?.onLoginResult(false, "No answer after submitting the code. Try again — or use the API-key route.")
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private var verifyTimer: javax.swing.Timer? = null

    fun cancelLogin() {
        verifyTimer?.stop()
        verifyTimer = null
        flow?.cancel()
        flow = null
        authUrl = null
        signingIn = false
    }

    fun onCleanResult() {
        prompted = false
    }

    fun maybePrompt() {
        if (ClaudeSettings.getInstance(project).provider != Provider.ANTHROPIC) return
        if (prompted) return
        prompted = true
        notifyLoginNeeded()
    }

    private fun notifyLoginNeeded() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "You don't seem to be logged in. Sign in to Claude to continue.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple("Sign in") { start() })
            .notify(project)
    }

    enum class Mode(val args: List<String>) {
        SUBSCRIPTION(listOf("auth", "login")),
        CONSOLE(listOf("auth", "login", "--console")),
        SSO(listOf("auth", "login", "--sso")),
    }

    fun start(mode: Mode = Mode.SUBSCRIPTION) {
        loginMode = mode
        val settings = ClaudeSettings.getInstance(project)
        if (settings.provider != Provider.ANTHROPIC) {
            notifyInfo(
                "Sign-in is only for the Anthropic provider. You're on ${settings.provider.label} — " +
                    "set its API key in Settings instead.",
            )
            return
        }
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            notifyMissingBinary()
            return
        }
        val cardUi = ui
        if (cardUi != null && startCardFlow(binary, cardUi)) return
        edt {
            if (openTerminal(binary)) return@edt
            log.info("IDE terminal unavailable for /login — falling back to the dialog-driven PTY flow")
            if (startNativePtyFlow(binary)) {
                notifyInfo("Signing in… your browser should open. Approve access there to finish.")
                return@edt
            }
            notifyError(
                "Couldn't start the sign-in flow. Run this in a terminal, then restart the chat:\n" +
                    TerminalLauncher.loginCommand(binary.absolutePath, loginMode.args),
            )
        }
    }

    private fun startCardFlow(binary: File, cardUi: LoginUi): Boolean {
        val env = System.getenv() + ClaudeSettings.getInstance(project).resolveEnv()
        signingIn = true
        val ptyFlow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env, args = loginMode.args)
        val started = ptyFlow.start(object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                authUrl = url
                edt { cardUi.onAuthUrl(url) }
            }

            override fun onCodeRequested() = edt { cardUi.onCodeRequested() }

            override fun onToken(token: String) {
                dev.lain.claudejb.settings.SecretStore.set(dev.lain.claudejb.settings.SecretStore.OAUTH_TOKEN, token)
            }

            override fun onResult(success: Boolean, message: String) = edt {
                verifyTimer?.stop()
                verifyTimer = null
                flow = null
                authUrl = null
                if (success) {
                    completeSignIn(binary) { ok, text ->
                        cardUi.onLoginResult(ok, if (ok) message else text)
                        if (ok) notifyInfo(text) else notifyError(text)
                    }
                } else {
                    signingIn = false
                    cardUi.onLoginResult(false, message)
                }
            }
        })
        if (started) flow = ptyFlow else signingIn = false
        return started
    }

    private fun openTerminal(binary: File): Boolean {
        val opened = TerminalLauncher.openAndRunCommand(
            project,
            listOf(binary.absolutePath) + loginMode.args,
            "claude login",
        )
        if (opened) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
                .createNotification(
                    "Claude Code",
                    "Finish signing in in the terminal — the browser opens automatically. " +
                        "When it confirms you're logged in, restart the chat to use it.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimple("Restart chat") { restartSession() })
                .notify(project)
        }
        return opened
    }

    private fun startNativePtyFlow(binary: File): Boolean {
        val env = System.getenv() + ClaudeSettings.getInstance(project).resolveEnv()
        signingIn = true
        val ptyFlow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env, args = loginMode.args)
        val started = ptyFlow.start(object : ClaudeLoginFlow.Listener {
            override fun onAuthUrl(url: String) {
                authUrl = url
            }

            override fun onCodeRequested() = edt { promptForCode(ptyFlow) }

            override fun onResult(success: Boolean, message: String) = edt {
                flow = null
                authUrl = null
                if (success) {
                    completeSignIn(binary) { ok, text -> if (ok) notifyInfo(text) else notifyError(text) }
                } else {
                    signingIn = false
                    notifyError(message)
                }
            }
        })
        if (started) flow = ptyFlow else signingIn = false
        return started
    }

    private fun completeSignIn(binary: File, done: (Boolean, String) -> Unit) {
        val env = ClaudeSettings.getInstance(project).resolveEnv()
        ApplicationManager.getApplication().executeOnPooledThread {
            val verified = AuthCli.status(binary, env)?.loggedIn == true
            val vaulted = if (verified) {
                dev.lain.claudejb.process.AccountProfile.capture()
                ClaudeSettings.getInstance(project).signedOut = false
                takeCustodyOfCredential()
            } else {
                log.warn("'auth login' exited 0 but 'auth status' reports no login — not banking a credential")
                false
            }
            edt {
                signingIn = false
                done(
                    verified,
                    when {
                        !verified -> "Signed in, but Claude Code still reports no account. Please try again."
                        vaulted -> "Signed in. Your credentials were moved into the IDE's password safe."
                        else -> "Signed in to Claude."
                    },
                )
                if (verified) restartSession()
            }
        }
    }

    private fun takeCustodyOfCredential(): Boolean {
        val vaulted = dev.lain.claudejb.process.CredentialsVault.harvest()
        val consoleKey = dev.lain.claudejb.process.ConsoleApiKey.harvest() ?: return vaulted
        dev.lain.claudejb.process.ApiKeyApproval.approve(consoleKey)
        ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, consoleKey)
        return true
    }

    private fun promptForCode(ptyFlow: ClaudeLoginFlow) {
        val urlHint = authUrl?.let { "\n\nIf the browser didn't open, visit:\n$it" }.orEmpty()
        val code = Messages.showInputDialog(
            project,
            "Approve access in your browser, then paste the authorization code here.$urlHint",
            "Sign in to Claude",
            null,
        )
        if (code.isNullOrBlank()) {
            ptyFlow.cancel()
            flow = null
            notifyInfo("Login canceled.")
        } else {
            ptyFlow.submitCode(code.trim())
        }
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 45_000
    }
}
