package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.process.AccountProfile
import dev.lain.claudejb.process.AuthCli
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.resolveEnv
import java.io.File

enum class Credential {
    HELD,

    NONE,

    UNKNOWN,
}

class AuthGate(
    private val project: Project,
    private val signInInProgress: () -> Boolean,
    private val launchEnv: () -> Map<String, String>,
    private val onProbed: (Boolean) -> Unit,
) {

    @Volatile
    var status: AuthCli.AuthState? = null
        private set

    @Volatile
    private var startupHarvestDone = false

    @Volatile private var binaryOwnLogin = false

    @Volatile private var ownLoginCheckedAt = 0L

    fun absorbExistingLoginOnce() {
        if (startupHarvestDone) return
        startupHarvestDone = true
        captureAccountIdentityOnce()
        CredentialsVault.harvest()
    }

    private fun captureAccountIdentityOnce() {
        if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
        if (AuthCli.stored()?.email != null) return
        if (!CredentialsVault.credentialsFile().isFile) return
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        AuthCli.status(binary, settings.resolveEnv())
    }

    fun hasCredential(settings: ClaudeSettings): Boolean = when (heldCredential(settings)) {
        Credential.HELD -> true
        Credential.NONE -> false
        Credential.UNKNOWN -> binaryHoldsOwnLogin(settings)
    }

    fun heldCredential(settings: ClaudeSettings): Credential {
        if (CredentialsVault.hasUsableToken()) return Credential.HELD
        if (CredentialsVault.canRenew()) return Credential.HELD
        if (SecretStore.get(SecretStore.OAUTH_TOKEN) != null) return Credential.HELD
        if (settings.getProviderApiKey(settings.provider).isNotBlank()) return Credential.HELD
        if (settings.state.sourceScript.isNotBlank()) return Credential.UNKNOWN
        val explicit = settings.resolveEnv()
        if (SecretStore.API_KEY in explicit || SecretStore.OAUTH_TOKEN in explicit) return Credential.HELD
        if (settings.state.signedOut) return Credential.NONE
        val probed = cachedBinaryLogin() ?: return Credential.UNKNOWN
        return if (probed) Credential.HELD else Credential.NONE
    }

    fun canRenewCredential(): Boolean = CredentialsVault.canRenew()

    private fun binaryHoldsOwnLogin(settings: ClaudeSettings): Boolean {
        cachedBinaryLogin()?.let { return it }
        val now = System.currentTimeMillis()
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return false
        val reply = AuthCli.status(binary, settings.resolveEnv())
        binaryOwnLogin = reply?.loggedIn == true
        ownLoginCheckedAt = now
        return binaryOwnLogin
    }

    private fun cachedBinaryLogin(): Boolean? =
        binaryOwnLogin.takeIf { System.currentTimeMillis() - ownLoginCheckedAt < OWN_LOGIN_TTL_MS }

    fun renew(binary: File, settings: ClaudeSettings): Boolean {
        if (signInInProgress()) return true
        if (!CredentialsVault.needsRenewal()) return true
        if (attemptRenewal(binary, settings)) return true
        ownLoginCheckedAt = 0
        return hasCredential(settings)
    }

    fun renewRejected(binary: File, settings: ClaudeSettings): Boolean {
        if (signInInProgress()) return false
        if (!CredentialsVault.canRenew()) return false
        return attemptRenewal(binary, settings)
    }

    private fun attemptRenewal(binary: File, settings: ClaudeSettings): Boolean {
        if (!CredentialsVault.renew(binary, settings.resolveEnv())) return false
        AccountProfile.invalidate()
        return true
    }

    fun probe() {
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val onOurEnv = AuthCli.status(binary, launchEnv()) ?: AuthCli.AuthState(loggedIn = false)
            status = identify(onOurEnv, binary, settings)
            onProbed(onOurEnv.loggedIn)
        }
    }

    private fun identify(onOurEnv: AuthCli.AuthState, binary: File, settings: ClaudeSettings): AuthCli.AuthState {
        if (!onOurEnv.loggedIn || onOurEnv.email != null || onOurEnv.orgName != null) return onOurEnv
        val identity = AuthCli.status(binary, settings.resolveEnv())
            ?.takeIf { it.email != null || it.orgName != null }
            ?: AuthCli.stored()
        return onOurEnv.copy(
            email = identity?.email,
            orgId = identity?.orgId,
            orgName = identity?.orgName,
            apiProvider = onOurEnv.apiProvider ?: identity?.apiProvider,
            subscriptionType = onOurEnv.subscriptionType ?: identity?.subscriptionType,
        )
    }

    companion object {
        private const val OWN_LOGIN_TTL_MS = 60_000L
    }
}
