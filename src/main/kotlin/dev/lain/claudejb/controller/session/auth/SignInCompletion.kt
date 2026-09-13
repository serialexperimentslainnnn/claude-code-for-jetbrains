package dev.lain.claudejb.controller.session.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.process.auth.AccountProfile
import dev.lain.claudejb.controller.process.auth.AuthCli
import dev.lain.claudejb.controller.process.credentials.ApiKeyApproval
import dev.lain.claudejb.controller.process.credentials.ConsoleApiKey
import dev.lain.claudejb.controller.process.credentials.CredentialsVault
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.model.settings.env.resolveEnv
import dev.lain.claudejb.util.thisLogger
import java.io.File

class SignInCompletion(
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
) {

    private val log = thisLogger()

    fun complete(binary: File, done: (Boolean, String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val env = ClaudeSettings.getInstance(project).resolveEnv()
            val verified = AuthCli.status(binary, env)?.loggedIn == true
            val vaulted = if (verified) {
                AccountProfile.capture()
                ClaudeSettings.getInstance(project).signedOut = false
                takeCustodyOfCredential()
            } else {
                log.warn("'auth login' exited 0 but 'auth status' reports no login — not banking a credential")
                false
            }
            edt {
                done(
                    verified,
                    when {
                        !verified -> "Signed in, but Claude Code still reports no account. Please try again."
                        vaulted -> "Signed in. Your credentials were moved into the IDE's password safe."
                        else -> "Signed in to Claude."
                    },
                )
            }
        }
    }

    private fun takeCustodyOfCredential(): Boolean {
        val vaulted = CredentialsVault.harvest()
        val consoleKey = ConsoleApiKey.harvest() ?: return vaulted
        ApiKeyApproval.approve(consoleKey)
        ClaudeSettings.getInstance(project).setProviderApiKey(Provider.ANTHROPIC, consoleKey)
        return true
    }
}
