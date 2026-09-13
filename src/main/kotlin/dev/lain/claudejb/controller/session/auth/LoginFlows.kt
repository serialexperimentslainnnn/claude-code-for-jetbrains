package dev.lain.claudejb.controller.session.auth

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.controller.process.auth.ClaudeLoginFlow
import dev.lain.claudejb.controller.session.SessionNotifier
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.SecretStore
import dev.lain.claudejb.model.settings.env.resolveEnv
import java.io.File

class LoginAttempt(
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val binary: File,
    private val mode: LoginCoordinator.Mode,
    private val ui: LoginCoordinator.LoginUi,
    private val host: Host,
    private val spawn: (List<String>, Map<String, String>, String?) -> Process = ClaudeLoginFlow::spawnPty,
) {

    interface Host {
        fun ptyEnded()

        fun finished(verified: Boolean)

        fun complete(binary: File, done: (Boolean, String) -> Unit)

        fun announce(text: String)
    }

    @Volatile private var pty: ClaudeLoginFlow? = null

    fun start(): Boolean {
        val env = System.getenv() + ClaudeSettings.getInstance(project).resolveEnv()
        val flow = ClaudeLoginFlow(binary.absolutePath, project.basePath, env, args = mode.args, spawn = spawn)
        val started = flow.start(listener)
        if (started) pty = flow
        return started
    }

    fun submitCode(code: String) {
        pty?.submitCode(code)
    }

    fun cancel() {
        pty?.cancel()
        pty = null
    }

    private val listener = object : ClaudeLoginFlow.Listener {
        override fun onAuthUrl(url: String) = edt { ui.onAuthUrl(url) }

        override fun onCodeRequested() = edt { ui.onCodeRequested() }

        override fun onToken(token: String) = SecretStore.set(SecretStore.OAUTH_TOKEN, token)

        override fun onResult(success: Boolean, message: String) = edt {
            host.ptyEnded()
            pty = null
            if (success) {
                host.complete(binary) { ok, text -> onCompleted(ok, if (ok) message else text, text) }
            } else {
                host.finished(false)
                ui.onLoginResult(false, message)
            }
        }
    }

    private fun onCompleted(verified: Boolean, forUi: String, text: String) {
        host.finished(verified)
        ui.onLoginResult(verified, forUi)
        if (verified) host.announce(text)
    }
}

class DialogLoginUi(
    private val project: Project,
    private val notifier: SessionNotifier,
    private val submitCode: (String) -> Unit,
    private val cancel: () -> Unit,
) : LoginCoordinator.LoginUi {

    @Volatile private var authUrl: String? = null

    override fun onAuthUrl(url: String) {
        authUrl = url
    }

    override fun onCodeRequested() {
        val urlHint = authUrl?.let { "\n\nIf the browser didn't open, visit:\n$it" }.orEmpty()
        val code = Messages.showInputDialog(
            project,
            "Approve access in your browser, then paste the authorization code here.$urlHint",
            "Sign in to Claude",
            null,
        )
        if (code.isNullOrBlank()) {
            cancel()
            notifier.info("Login canceled.")
        } else {
            submitCode(code.trim())
        }
    }

    override fun onLoginResult(success: Boolean, message: String) {
        if (!success) notifier.error(message)
    }
}
