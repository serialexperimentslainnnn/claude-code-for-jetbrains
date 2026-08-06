package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.SecretStore

/**
 * Headless: [SecretStore] over the test fixture's in-memory PasswordSafe. What is pinned here is the
 * CONTRACT the sign-in card and the launch-env overlay rely on — the two entries are mutually exclusive,
 * and the overlay never overrides an explicitly-set name.
 */
class SecretStoreHeadlessTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SecretStore.clearAll()
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

    fun `test clearAll leaves nothing behind for the overlay`() {
        SecretStore.set(SecretStore.OAUTH_TOKEN, "sk-ant-oat")
        SecretStore.clearAll()
        assertTrue(SecretStore.envOverlay(emptySet()).isEmpty())
    }
}
