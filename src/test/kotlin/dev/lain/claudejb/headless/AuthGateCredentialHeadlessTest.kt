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

/**
 * Headless: [AuthGate.heldCredential] answers **held / none / unknown**, and the third answer is the one that
 * carries the behaviour.
 *
 * What it is for: the sign-in card is drawn from a "no", so an answer of "no" that merely means "nobody has
 * asked the binary yet" raises that card at a user who is signed in — and on a host where the binary keeps
 * its credentials in an OS store rather than a file, every signed-in user reaches exactly that branch. The
 * distinction under test is therefore between [Credential.NONE], which is a decision, and
 * [Credential.UNKNOWN], which is the absence of one and must draw nothing.
 *
 * **Nothing here may reach the binary.** Every case is chosen so the answer is settled by the safe or the
 * settings alone: a case that fell through to the `auth status` probe would spawn the real `claude` with the
 * developer's own environment, from a test. The complementary half — that [AuthGate.heldCredential] cannot
 * spawn one even if a case did fall through — is `BootStateContractTest`, which reads the source.
 *
 * The fixtures are the ones the vault's own tests use, for the reason stated there: a temporary home
 * ([CredentialsVault.homeOverride]) because harvesting MOVES a credential, and a store of this class's own
 * ([SecretStore.storeOverride]) because the fixture's PasswordSafe belongs to an Application the platform
 * reuses for the whole run.
 */
class AuthGateCredentialHeadlessTest : BasePlatformTestCase() {

    private lateinit var home: File

    private val settings get() = ClaudeSettings.getInstance(project)

    /** A gate over the fixture project. Nothing is signed in, so no leg of it may talk to a process. */
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
        // `readFailed` lives on an object and would otherwise carry a previous class's veto into these saves.
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
        // The blocking question agrees, and reaches the same conclusion without a probe: `signedOut` is
        // checked before the branch that would spawn one.
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
            // Even against the one thing that otherwise decides outright: the script may export a credential,
            // and an identity the user configured deliberately is not what Log out clears.
            it.signedOut = true
        }

        assertEquals(Credential.UNKNOWN, gate().heldCredential(settings))
    }

    fun `test signing out outranks a key held for another provider only`() {
        settings.setProviderApiKey(settings.provider, FAKE_SECRET)
        settings.update { it.signedOut = true }

        // The key wins: `signedOut` is about the subscription login, and it is checked after what we hold.
        assertEquals(Credential.HELD, gate().heldCredential(settings))
    }

    private companion object {
        /** Not a credential, and not shaped like one: nothing here is ever handed to a process. */
        const val FAKE_SECRET = "fixture-value-not-a-credential"
    }
}
