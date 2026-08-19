package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.session.AuthGate
import dev.lain.claudejb.session.Credential
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore
import java.io.File
import java.nio.file.Files

class AuthGateCredentialHeadlessTest : BasePlatformTestCase() {

    private lateinit var home: File

    private val settings get() = ClaudeSettings.getInstance(project)

    private fun gate() = AuthGate(
        project = project,
        signInInProgress = { false },
        launchEnv = { emptyMap() },
        onProbed = {},
    )

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claudejb-home").toFile()
        CredentialsVault.homeOverride = home
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
        settings.replaceState(ClaudeSettings.State())
    }

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
            CredentialsVault.homeOverride = null
            home.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test nothing held and no answer from the binary is unknown, not signed out`() {
        assertEquals(Credential.UNKNOWN, gate().heldCredential(settings))
    }

    fun `test an explicit sign-out decides outright`() {
        settings.update { it.signedOut = true }

        assertEquals(Credential.NONE, gate().heldCredential(settings))
        assertFalse(gate().hasCredential(settings))
    }

    fun `test a provider API key is an identity`() {
        settings.setProviderApiKey(settings.provider, FAKE_SECRET)

        assertEquals(Credential.HELD, gate().heldCredential(settings))
        assertTrue(gate().hasCredential(settings))
    }

    fun `test a vaulted OAuth token is an identity`() {
        SecretStore.set(SecretStore.OAUTH_TOKEN, FAKE_SECRET)

        assertEquals(Credential.HELD, gate().heldCredential(settings))
        assertTrue(gate().hasCredential(settings))
    }

    fun `test a configured source script defers instead of deciding`() {
        settings.update {
            it.sourceScript = "/nowhere/claude-env.sh"
            it.signedOut = true
        }

        assertEquals(Credential.UNKNOWN, gate().heldCredential(settings))
    }

    fun `test signing out outranks a key held for another provider only`() {
        settings.setProviderApiKey(settings.provider, FAKE_SECRET)
        settings.update { it.signedOut = true }

        assertEquals(Credential.HELD, gate().heldCredential(settings))
    }

    private companion object {
        const val FAKE_SECRET = "fixture-value-not-a-credential"
    }
}
