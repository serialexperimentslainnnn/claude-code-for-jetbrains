package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.SecretStore

/**
 * Headless: [SecretStore] over a store of this test's own ([SecretStore.storeOverride]). What is pinned here
 * is the CONTRACT the sign-in card and the launch-env overlay rely on — the two entries are mutually
 * exclusive, and the overlay never overrides an explicitly-set name.
 *
 * The store is per-method rather than the fixture's PasswordSafe because that one is an application service
 * on an Application the platform reuses for the whole run: shared by every test class in the JVM, and the
 * entries this class writes (`CLAUDE_CODE_OAUTH_TOKEN`, `CLAUDE_CREDENTIALS_JSON`) are the very ones
 * [dev.lain.claudejb.process.CredentialsVault]'s tests read.
 */
class SecretStoreHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
    }

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    fun `test set get clear round-trips a stored entry`() {
        assertNull(SecretStore.get(SecretStore.OAUTH_TOKEN))
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        assertEquals("sk-ant-oat", SecretStore.get(SecretStore.OAUTH_TOKEN))
        SecretStore.clear(SecretStore.OAUTH_TOKEN)
        assertNull(SecretStore.get(SecretStore.OAUTH_TOKEN))
    }

    fun `test setting one credential clears the other — the auth modes are exclusive`() {
        SecretStore.set(SecretStore.CREDENTIALS_JSON, """{"claudeAiOauth":{}}""")
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        assertNull(
            "a stale subscription blob must not silently win over the token just set",
            SecretStore.get(SecretStore.CREDENTIALS_JSON),
        )
        assertEquals("sk-ant-oat", SecretStore.get(SecretStore.OAUTH_TOKEN))
    }

    fun `test the API key is NOT kept here — it lives in its own provider slot`() {
        // ANTHROPIC_API_KEY is an env-var NAME this store knows about, not an entry it holds: the key is
        // stored like every other provider's, under providerApiKey:<id>, so the sign-in card and Settings ▸
        // Provider cannot end up disagreeing about which key the binary ran with.
        try {
            SecretStore.set(SecretStore.API_KEY, "sk-ant-key")
            fail("the API key must not be storable here")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown secret"))
        }
    }

    fun `test unknown names are refused rather than stored under a typo`() {
        // try/catch rather than assertThrows: under the JUnit3 fixture runner the assertThrows lambda
        // compiles to a synthetic method whose name starts with "test", which the runner then tries to
        // execute as a test of its own and fails on ("Test method isn't public").
        try {
            SecretStore.set("ANTHROPIC_APIKEY", "x")
            fail("expected IllegalArgumentException for an unknown secret name")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown secret"))
        }
    }

    fun `test envOverlay yields stored credentials but never overrides an explicit name`() {
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        assertEquals(
            mapOf(SecretStore.OAUTH_TOKEN to "sk-ant-oat"),
            SecretStore.envOverlay(explicitNames = emptySet()),
        )
        // A hand-written Settings/env value keeps winning: the overlay must NOT offer a competing one.
        assertTrue(SecretStore.envOverlay(explicitNames = setOf(SecretStore.OAUTH_TOKEN)).isEmpty())
    }

    /**
     * SECURITY: an explicit `ANTHROPIC_API_KEY` in the env means the session is NOT the vaulted subscription
     * identity, so the overlay must contribute nothing at all.
     *
     * The env carries that name in exactly two cases and both say the same thing: the user wrote a key by
     * hand, or a third-party provider is selected — in which case `Provider.launchEnv` has also pointed
     * `ANTHROPIC_BASE_URL` at that provider. Handing `CLAUDE_CODE_OAUTH_TOKEN` to a process aimed at
     * `api.deepseek.com` puts an Anthropic subscription token one binary-precedence rule away from a
     * third-party endpoint. `CredentialsVault.envOverlay` already refuses on exactly this condition; the two
     * overlays feed the same environment and cannot be allowed to disagree.
     */
    fun `test an explicit API key suppresses the overlay entirely`() {
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        assertTrue(
            "a subscription token must never ride along with a third-party provider's key",
            SecretStore.envOverlay(explicitNames = setOf(SecretStore.API_KEY, "ANTHROPIC_BASE_URL")).isEmpty(),
        )
    }

    fun `test clearAll leaves nothing behind for the overlay`() {
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        SecretStore.clearAll()
        assertTrue(SecretStore.envOverlay(emptySet()).isEmpty())
    }
}
