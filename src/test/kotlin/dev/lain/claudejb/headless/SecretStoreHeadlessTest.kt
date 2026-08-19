package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.SecretStore

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
        try {
            SecretStore.set(SecretStore.API_KEY, "sk-ant-key")
            fail("the API key must not be storable here")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown secret"))
        }
    }

    fun `test unknown names are refused rather than stored under a typo`() {
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
        assertTrue(SecretStore.envOverlay(explicitNames = setOf(SecretStore.OAUTH_TOKEN)).isEmpty())
    }

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
