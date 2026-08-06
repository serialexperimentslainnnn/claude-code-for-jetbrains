package dev.lain.claudejb.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.process.ApiKeyApproval
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.process.BinaryInstall
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.process.TerminalLauncher
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.LoginCoordinator
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.ui.jcef.JcefBridge
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.swing.Timer

/**
 * Everything the two onboarding cards need from the host: installing the binary, validating a manual path,
 * watching for an install to finish, and the sign-in flow's host side ([LoginCoordinator.LoginUi]).
 *
 * A collaborator of [JcefChatPanel] rather than more methods ON it — the panel stays the thin assembler the
 * architecture demands, and this file owns one concern end to end. [exec] is the panel's `host.exec`.
 */
internal class OnboardingController(
    private val project: Project,
    private val session: ClaudeSession,
    private val exec: (String) -> Unit,
) : LoginCoordinator.LoginUi {

    /**
     * Always running, not just while a card is up: which screen this tab owes the user is a question about
     * the world — is the binary installed, do we hold a credential — and the world changes while the tab
     * sits there. Answering it once at construction is what made an install or a sign-in performed outside
     * the card require closing and reopening the tab to take effect.
     *
     * The check is silent and cheap (a filesystem stat and a safe read, no process spawn), and
     * [ClaudeSession.refreshBootState] returns immediately once a session is running.
     */
    private val bootWatcher = Timer(BOOT_WATCH_MS) { tick() }.apply {
        isRepeats = true
        start()
    }

    private fun tick() {
        // Runs while the session is UP too, and that is the point: losing the binary or the credential has
        // to walk the flow backwards (stop the process, show the matching screen), not sit there with a
        // chat whose identity is gone.
        // BLOCKING: stats the filesystem and reads the PasswordSafe (a keychain round-trip on some hosts).
        ApplicationManager.getApplication().executeOnPooledThread { session.refreshBootState() }
    }

    /**
     * Panel state push. The watcher runs unconditionally now, so there is nothing to start or stop — the
     * only thing left is announcing an install we launched, once, when the binary actually turns up. The
     * terminal tab running the installer may well be covering the chat, so a notification is the only place
     * the user reliably sees it.
     */
    fun onStateChanged() {
        if (installLaunched && !session.binaryMissing) {
            installLaunched = false
            notifyInfo("Claude Code installed", "The claude binary was found — starting the session.")
        }
    }

    fun dispose() = bootWatcher.stop()

    fun handle(m: JcefBridge.Msg.SessionControl): Boolean {
        when (m) {
            is JcefBridge.Msg.InstallClaude -> runInstaller(m.method)

            is JcefBridge.Msg.SetBinaryPath -> validateAndUseBinaryPath(m.path)

            JcefBridge.Msg.RecheckBinary -> recheckBinary(announceFailure = true)

            JcefBridge.Msg.LoginSubscription -> {
                pushAuthState("waiting")
                session.startLogin()
            }

            is JcefBridge.Msg.UseApiKey -> useApiKey(m.key)

            is JcefBridge.Msg.SubmitLoginCode -> {
                pushAuthState("verifying")
                session.submitLoginCode(m.code)
            }

            JcefBridge.Msg.CancelLogin -> {
                session.cancelLogin()
                pushAuthState("idle")
            }

            JcefBridge.Msg.DismissAuth -> session.dismissLoginCard()

            JcefBridge.Msg.Logout -> logout()

            else -> return false
        }
        return true
    }

    // ── missing-binary card ──────────────────────────────────────────────────────────────────────────────

    /** Runs the chosen official installer in the IDE terminal, where the user watches every line of it. */
    private fun runInstaller(methodId: String) {
        val method = BinaryInstall.method(methodId) ?: return
        val launched = TerminalLauncher.isAvailable() &&
            TerminalLauncher.openAndRunCommand(project, method.argv, "Install Claude Code")
        if (!launched) {
            // No Terminal plugin: the card's `display` text is the fallback — tell the user to run it
            // themselves rather than silently doing nothing.
            pushBootError("The IDE terminal is unavailable — run this in a shell:  ${method.display}")
            return
        }
        // The installer is now running in a terminal tab; the watcher is already looking. All this records is
        // that we owe the user a "it worked" when the binary shows up.
        installLaunched = true
    }

    /** Set while an installer we launched is running, so its success can be announced exactly once. */
    private var installLaunched = false

    /**
     * Validates a user-typed path OFF the EDT (it runs `--version`, seconds on a cold start), then either
     * persists it and starts the session, or puts the reason on the card.
     */
    private fun validateAndUseBinaryPath(rawPath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val verdict = BinaryInstall.validate(rawPath)
            ApplicationManager.getApplication().invokeLater {
                when (verdict) {
                    is BinaryInstall.Validation.Ok -> {
                        ClaudeSettings.getInstance(project).state.claudePath = verdict.binary.absolutePath
                        session.start()
                    }

                    is BinaryInstall.Validation.Invalid -> pushBootError(verdict.reason)
                }
            }
        }
    }

    /**
     * The card's "Check again" button. The periodic watcher does the same work silently; this exists so an
     * impatient click gets an answer instead of nothing, and so a failure can be said out loud once.
     */
    private fun recheckBinary(announceFailure: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            session.refreshBootState()
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

    // ── sign-in card (LoginCoordinator.LoginUi) ──────────────────────────────────────────────────────────

    /** Host → card: one method moves the whole card; the card is a pure function of `{step,url,message}`. */
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
        // Success needs no card step: the restart's state push clears needsLogin and the card falls away.
        if (!success) pushAuthState("error", message = message)
    }

    /**
     * The card's API-key route: the key goes to the IDE's PasswordSafe ([SecretStore]) — application-level
     * and encrypted, never the project-level XML — and the session relaunches with it in the environment.
     * The value exists in this method and the safe, nowhere else: not logged, not echoed, not persisted in
     * settings.
     */
    private fun useApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            pushAuthState("error", message = "Enter an API key first.")
            return
        }
        pushAuthState("verifying")
        // Off-EDT: this both writes a file and runs the binary.
        ApplicationManager.getApplication().executeOnPooledThread {
            // Record the approval BEFORE validating — the probe is itself a non-interactive run, so an
            // unapproved key would fail it for the same reason it failed every turn.
            ApiKeyApproval.approve(trimmed)
            val binary = ClaudeBinaryLocator.locate(ClaudeSettings.getInstance(project).claudePath)
            val state = binary?.let { AuthCli.status(it, mapOf(SecretStore.API_KEY to trimmed)) }
            ApplicationManager.getApplication().invokeLater {
                if (state != null && !state.loggedIn) {
                    // Never file a credential the binary just refused: it would come back as a failed turn
                    // on every launch, with nothing on screen tying it to the key that was typed.
                    pushAuthState("error", message = "That API key was refused. Check it and try again.")
                    return@invokeLater
                }
                // Its own provider slot — the same one Settings ▸ Provider uses, so the card and that field
                // are two doors onto one credential. A DeepSeek key lives under its own id and is untouched.
                ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, trimmed)
                session.dismissLoginCard()
                session.restart()
            }
        }
    }

    /**
     * Log out = every place a credential of ours can be: the IDE safe (API key, and the vaulted
     * credentials file) and any file the vault materialized on disk.
     *
     * Deliberately NOT `claude auth logout`. The plugin's credentials live in the safe and only visit the
     * disk while a session runs ([CredentialsVault]) — clearing the safe IS the logout. Shelling out to
     * the binary would additionally destroy whatever the user's own terminal CLI had, which is not this
     * button's business. Off-EDT for the file work; the restart's probe raises the sign-in card again.
     */
    private fun logout() {
        // STOP FIRST, and this order is the whole correctness of the button.
        //
        // The running binary holds the old identity, so leaving it alive means "signed out" while the very
        // next turn still works. Worse, `stop()` harvests the credentials file back into the safe — so
        // clearing first and stopping afterwards could put the credential straight back and silently undo
        // the logout. Stop, then clear, then start into a session that has nothing to run as.
        session.stop()
        ApplicationManager.getApplication().executeOnPooledThread {
            SecretStore.clearAll()
            CredentialsVault.clear()
            // The Anthropic key too — it is one of this plugin's identities. Other providers' keys are NOT
            // cleared: signing out of Claude is not a reason to lose an unrelated DeepSeek credential.
            ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, "")
            ApplicationManager.getApplication().invokeLater {
                notifyInfo("Signed out of Claude", "Stored credentials were removed from the IDE.")
                // Finds no credential and raises the sign-in card instead of launching. See
                // ClaudeSession.hasCredential.
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
        /** Cadence of the missing-binary watcher: pure file-existence checks, no process spawn. */
        const val BOOT_WATCH_MS = 3_000
    }
}
