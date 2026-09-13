package dev.lain.claudejb.controller.commands

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.process.BinaryInstall
import dev.lain.claudejb.controller.process.ClaudeBinaryLocator
import dev.lain.claudejb.controller.process.TerminalLauncher
import dev.lain.claudejb.controller.process.auth.AccountProfile
import dev.lain.claudejb.controller.process.auth.AuthCli
import dev.lain.claudejb.controller.process.credentials.ApiKeyApproval
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.controller.session.auth.LoginCoordinator
import dev.lain.claudejb.model.bridge.JcefBridge
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.model.settings.SecretStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.swing.Timer

internal class OnboardingController(
    private val project: Project,
    private val session: ClaudeSession,
    private val exec: (String) -> Unit,
) : LoginCoordinator.LoginUi {

    private val bootWatcher = Timer(BOOT_WATCH_MS) { tick() }.apply {
        isRepeats = true
        start()
    }

    private fun tick() {
        ApplicationManager.getApplication().executeOnPooledThread { session.lifecycle.refreshBootState() }
    }

    fun onStateChanged() {
        if (installLaunched && !session.lifecycle.binaryMissing) {
            installLaunched = false
            notifyInfo("Claude Code installed", "The claude binary was found — starting the session.")
        }
    }

    fun dispose() = bootWatcher.stop()

    fun handle(m: Msg.Onboarding) {
        when (m) {
            is Msg.InstallClaude -> runInstaller(m.method)

            is Msg.SetBinaryPath -> validateAndUseBinaryPath(m.path)

            Msg.RecheckBinary -> recheckBinary(announceFailure = true)

            Msg.LoginSubscription -> {
                pushAuthState("waiting")
                session.login.start(LoginCoordinator.Mode.SUBSCRIPTION)
            }

            Msg.LoginConsole -> {
                pushAuthState("waiting")
                session.login.start(LoginCoordinator.Mode.CONSOLE)
            }

            is Msg.UseApiKey -> useApiKey(m.key)

            is Msg.SubmitLoginCode -> {
                pushAuthState("verifying")
                session.login.submitCode(m.code)
            }

            Msg.CancelLogin -> {
                session.login.cancelLogin()
                pushAuthState("idle")
            }

            Msg.DismissAuth -> session.lifecycle.dismissLoginCard()

            Msg.Logout -> logout()
        }
    }

    private fun runInstaller(methodId: String) {
        val method = BinaryInstall.method(methodId) ?: return
        val launched = TerminalLauncher.isAvailable() &&
            TerminalLauncher.openAndRunCommand(project, method.argv, "Install Claude Code")
        if (!launched) {
            pushBootError("The IDE terminal is unavailable — run this in a shell:  ${method.display}")
            return
        }
        installLaunched = true
    }

    private var installLaunched = false

    private fun validateAndUseBinaryPath(rawPath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val verdict = BinaryInstall.validate(rawPath)
            ApplicationManager.getApplication().invokeLater {
                when (verdict) {
                    is BinaryInstall.Validation.Ok -> {
                        ClaudeSettings.getInstance(project)
                            .update { it.claudePath = verdict.binary.absolutePath }
                        session.start()
                    }

                    is BinaryInstall.Validation.Invalid -> pushBootError(verdict.reason)
                }
            }
        }
    }

    private fun recheckBinary(announceFailure: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            session.lifecycle.refreshBootState()
            val found = ClaudeBinaryLocator.locate(ClaudeSettings.getInstance(project).claudePath) != null
            if (found || !announceFailure) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                pushBootError("Still not found. If the install just finished, give it a moment — this card checks again on its own.")
            }
        }
    }

    private fun pushBootError(message: String) {
        exec("window.cc.bootPathError && window.cc.bootPathError(" + JcefBridge.jsString(message) + ")")
    }

    private fun pushAuthState(step: String, url: String? = null, message: String? = null) {
        val payload = buildJsonObject {
            put("step", JsonPrimitive(step))
            url?.let { put("url", JsonPrimitive(it)) }
            message?.let { put("message", JsonPrimitive(it)) }
        }
        exec("window.cc.authState && window.cc.authState($payload)")
    }

    override fun onAuthUrl(url: String) = pushAuthState("url", url = url)

    override fun onCodeRequested() = pushAuthState("code")

    override fun onLoginResult(success: Boolean, message: String) {
        if (!success) pushAuthState("error", message = message)
    }

    private fun useApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            pushAuthState("error", message = "Enter an API key first.")
            return
        }
        pushAuthState("verifying")
        ApplicationManager.getApplication().executeOnPooledThread {
            ApiKeyApproval.approve(trimmed)
            val binary = ClaudeBinaryLocator.locate(ClaudeSettings.getInstance(project).claudePath)
            val state = binary?.let { AuthCli.status(it, mapOf(SecretStore.API_KEY to trimmed)) }
            ApplicationManager.getApplication().invokeLater {
                if (state != null && !state.loggedIn) {
                    pushAuthState("error", message = "That API key was refused. Check it and try again.")
                    return@invokeLater
                }
                ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, trimmed)
                ClaudeSettings.getInstance(project).signedOut = false
                session.lifecycle.dismissLoginCard()
                session.restart()
            }
        }
    }

    internal fun logout() {
        ClaudeSettings.getInstance(project).signedOut = true
        session.stop()
        ApplicationManager.getApplication().executeOnPooledThread {
            SecretStore.clearAll()
            CredentialsVault.clear()
            AccountProfile.invalidate()
            ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, "")
            ApplicationManager.getApplication().invokeLater {
                notifyInfo("Signed out of Claude", "Stored credentials were removed from the IDE.")
                session.start()
            }
        }
    }

    private fun notifyInfo(title: String, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code")
            .createNotification(title, message, NotificationType.INFORMATION)
            .notify(project)
    }

    private companion object {
        const val BOOT_WATCH_MS = 3_000
    }
}
