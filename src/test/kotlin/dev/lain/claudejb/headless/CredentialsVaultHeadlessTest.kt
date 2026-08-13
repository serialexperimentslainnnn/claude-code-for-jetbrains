package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.process.CredentialsVault
import dev.lain.claudejb.settings.SecretStore
import java.io.File
import java.nio.file.Files

/**
 * Headless: [CredentialsVault] moves the binary's credentials file into the IDE's PasswordSafe and back.
 *
 * The invariant these pin is the whole point of the vault — **between sessions the credential is in the
 * encrypted safe and NOT on the disk**. The full-consent OAuth login writes plaintext JSON to
 * `~/.claude/.credentials.json`, readable by anything running as the user; the vault reduces its lifetime
 * to the session's.
 *
 * These run against a TEMPORARY home ([CredentialsVault.homeOverride]), never the real one. That is not
 * tidiness: harvest MOVES a credential, so a run that died between taking the file and restoring it would
 * sign the developer out of their own CLI for real.
 *
 * And against a credential store of their own ([SecretStore.storeOverride]), for the sibling reason: the
 * fixture's PasswordSafe is an application service on an Application the platform reuses for the whole run,
 * so `CLAUDE_CREDENTIALS_JSON` written here was visible to — and clearable by — every other test class in
 * the JVM.
 */
class CredentialsVaultHeadlessTest : BasePlatformTestCase() {

    private val file get() = CredentialsVault.credentialsFile()
    private lateinit var home: File

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claudejb-home").toFile()
        CredentialsVault.homeOverride = home
        SecretStore.storeOverride = mutableMapOf()
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

    /** A credentials blob whose access token expires [inMs] from now. */
    private fun blob(token: String, inMs: Long) =
        """{"claudeAiOauth":{"accessToken":"$token","expiresAt":${System.currentTimeMillis() + inMs}}}"""

    /** The real shape the binary writes: an access token AND the refresh token that outlives it. */
    private fun renewable(accessInMs: Long, refreshInMs: Long): String {
        val now = System.currentTimeMillis()
        return """
            {"claudeAiOauth":{"accessToken":"stale","expiresAt":${now + accessInMs},
             "refreshToken":"rt","refreshTokenExpiresAt":${now + refreshInMs},
             "scopes":["user:profile","user:inference"]}}
        """.trimIndent()
    }

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    fun `test harvest moves the file into the safe and deletes it`() {
        file.parentFile?.mkdirs()
        file.writeText("""{"claudeAiOauth":{"accessToken":"secret-token"}}""")

        assertTrue("harvest should report taking something", CredentialsVault.harvest())
        assertFalse("the credential must not remain on disk", file.exists())
        assertEquals(
            """{"claudeAiOauth":{"accessToken":"secret-token"}}""",
            SecretStore.get(SecretStore.CREDENTIALS_JSON),
        )
    }

    fun `test nothing ever writes the credential back to disk`() {
        // The invariant, stated as a test: there is no code path that re-creates the plaintext file. The
        // credential leaves the safe only through the child process ENVIRONMENT.
        file.parentFile?.mkdirs()
        file.writeText(blob("live-token", inMs = 6 * 60 * 60 * 1000))

        assertTrue(CredentialsVault.harvest())
        assertFalse(file.exists())
        // Everything a launch does with the vault, twice over — the file must stay gone.
        repeat(2) {
            CredentialsVault.envOverlay(emptySet())
            CredentialsVault.hasUsableToken()
            CredentialsVault.harvest()
        }
        assertFalse("the vault must never re-create the plaintext credential", file.exists())
    }

    fun `test harvest is a no-op with no file`() {
        assertFalse(CredentialsVault.harvest())
        assertFalse(file.exists())
    }

    fun `test a blank file is left alone rather than overwriting the safe with nothing`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, """{"good":true}""")
        file.parentFile?.mkdirs()
        file.writeText("   ")

        assertFalse("a half-written file is not a credential", CredentialsVault.harvest())
        assertEquals("""{"good":true}""", SecretStore.get(SecretStore.CREDENTIALS_JSON))
    }

    fun `test clear wipes both the safe entry and the file`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, """{"a":1}""")
        // A file left by the terminal CLI (nothing here writes one) must go too: Log out means gone.
        file.parentFile?.mkdirs()
        file.writeText("""{"a":1}""")

        CredentialsVault.clear()
        assertNull(SecretStore.get(SecretStore.CREDENTIALS_JSON))
        assertFalse(file.exists())
    }

    fun `test a live token reaches the binary through the environment, with no file`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, blob("live-token", inMs = 6 * 60 * 60 * 1000))

        val env = CredentialsVault.envOverlay(emptySet())
        assertEquals(mapOf(SecretStore.OAUTH_TOKEN to "live-token"), env)
        // The whole point: the session authenticates with nothing on disk at all.
        assertTrue(CredentialsVault.hasUsableToken())
        assertFalse(file.exists())
    }

    fun `test an expiring token is not handed out, and with no refresh token it is not an identity`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, blob("stale-token", inMs = 60_000))

        assertTrue("an expiring token must not be handed out", CredentialsVault.envOverlay(emptySet()).isEmpty())
        assertFalse(CredentialsVault.hasUsableToken())
        // Nothing to renew from: this blob carries an access token and nothing else.
        assertFalse(CredentialsVault.canRenew())
        assertFalse(CredentialsVault.needsRenewal())
    }

    fun `test an expired token with a live refresh token is still an identity, pending renewal`() {
        // THE REBOOT CASE. The access token is issued for hours, so an overnight restart always lands here;
        // answering "signed out" is what put the sign-in card in front of the user every morning.
        SecretStore.set(SecretStore.CREDENTIALS_JSON, renewable(accessInMs = -60_000, refreshInMs = day * 20))

        assertFalse(CredentialsVault.hasUsableToken())
        assertTrue("a live refresh token is an identity one renewal away", CredentialsVault.canRenew())
        assertTrue(CredentialsVault.needsRenewal())
    }

    fun `test a live access token needs no renewal`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, renewable(accessInMs = 6 * hour, refreshInMs = day * 20))

        assertTrue(CredentialsVault.hasUsableToken())
        assertFalse("nothing to renew while the token is live", CredentialsVault.needsRenewal())
    }

    fun `test an expired refresh token cannot renew`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, renewable(accessInMs = -60_000, refreshInMs = -day))

        assertFalse(CredentialsVault.canRenew())
        assertFalse("a spent refresh token must lead to the sign-in card", CredentialsVault.needsRenewal())
    }

    fun `test renewal needs the scopes the binary demands`() {
        // The non-interactive path is driven by CLAUDE_CODE_OAUTH_SCOPES alongside the refresh token, and a
        // blob that cannot state the grant it was issued under is not renewable — better to say so here than
        // to spawn a process that exits 1.
        SecretStore.set(
            SecretStore.CREDENTIALS_JSON,
            """{"claudeAiOauth":{"accessToken":"stale","expiresAt":0,"refreshToken":"rt","scopes":[]}}""",
        )

        assertFalse(CredentialsVault.canRenew())
    }

    fun `test a refresh token with no recorded expiry is given the benefit of the doubt`() {
        SecretStore.set(
            SecretStore.CREDENTIALS_JSON,
            """{"claudeAiOauth":{"accessToken":"stale","expiresAt":0,"refreshToken":"rt","scopes":["a"]}}""",
        )

        // The endpoint is the authority on whether it is still good; a wrong guess costs one failed renewal,
        // refusing costs a sign-in nobody needed.
        assertTrue(CredentialsVault.canRenew())
    }

    fun `test an explicit credential outranks the vaulted one`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, blob("vaulted", inMs = 6 * 60 * 60 * 1000))

        assertTrue(CredentialsVault.envOverlay(setOf(SecretStore.API_KEY)).isEmpty())
        assertTrue(CredentialsVault.envOverlay(setOf(SecretStore.OAUTH_TOKEN)).isEmpty())
    }

    fun `test an unparseable blob is not an identity`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, "not json at all")

        assertTrue(CredentialsVault.envOverlay(emptySet()).isEmpty())
        assertFalse(CredentialsVault.hasUsableToken())
    }

    fun `test the credentials blob is never offered to the child environment`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, """{"a":1}""")
        // It is file-shaped, not an env var: leaking it into the environment would put a bearer credential
        // in a place nothing reads it from, for no benefit.
        assertTrue(SecretStore.envOverlay(emptySet()).isEmpty())
    }
}
