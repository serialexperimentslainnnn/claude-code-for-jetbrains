package dev.lain.claudejb.headless

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore

class SecretStoreIsolationHeadlessTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    private fun assertFixtureSafe() {
        assertTrue(
            "this test reads PasswordSafe directly and may only ever do so in a unit-test JVM",
            ApplicationManager.getApplication().isUnitTestMode,
        )
    }

    private fun inTheSafe(name: String): String? {
        assertFixtureSafe()
        return PasswordSafe.instance.getPassword(CredentialAttributes(generateServiceName("Claude Code", name)))
    }

    fun `test a test JVM with no store installed cannot reach the PasswordSafe`() {
        SecretStore.storeOverride = null
        assertTrue("precondition: the store must be inert here", SecretStore.inert())

        SecretStore.set(SecretStore.OAUTH_TOKEN, "must-not-reach-the-safe")

        assertNull("an inert store must not write through to the PasswordSafe", inTheSafe(SecretStore.OAUTH_TOKEN))
        assertNull("an inert store must not read through either", SecretStore.get(SecretStore.OAUTH_TOKEN))
        assertTrue(SecretStore.envOverlay(emptySet()).isEmpty())
    }

    fun `test an installed store isolates its entries and does not outlive the test`() {
        SecretStore.storeOverride = mutableMapOf()
        SecretStore.set(SecretStore.OAUTH_TOKEN, "isolated")

        assertEquals("isolated", SecretStore.get(SecretStore.OAUTH_TOKEN))
        assertNull("an installed store must not write through either", inTheSafe(SecretStore.OAUTH_TOKEN))

        SecretStore.storeOverride = null
        assertNull("the values must die with the store", SecretStore.get(SecretStore.OAUTH_TOKEN))
    }

    fun `test one test cannot see another test's values`() {
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.save(ClaudeSettings.State().apply { model = "written-by-the-first-test" })
        assertEquals("written-by-the-first-test", SettingsStore.load().model)

        SecretStore.storeOverride = mutableMapOf()
        assertEquals(
            "a fresh store must not carry the previous test's configuration",
            ClaudeSettings.State().model,
            SettingsStore.load().model,
        )
    }

    fun `test an inert store is not a failed read`() {
        SecretStore.storeOverride = null
        SettingsStore.load()

        SecretStore.storeOverride = mutableMapOf()
        assertTrue(
            "an inert read must not veto the next save",
            SettingsStore.save(ClaudeSettings.State().apply { model = "saved-after-an-inert-read" }),
        )
        assertEquals("saved-after-an-inert-read", SettingsStore.load().model)
    }

    fun `test an inert store refuses to save rather than reporting a success nothing kept`() {
        SecretStore.storeOverride = null

        assertFalse(SettingsStore.save(ClaudeSettings.State().apply { model = "nowhere-to-go" }))
        assertFalse(
            "a migration into a store that is not there has not migrated anything",
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
    }

    fun `test nothing leaves anything in the Application-wide safe`() {
        assertFixtureSafe()
        val leaked = listOf(
            SecretStore.OAUTH_TOKEN,
            SecretStore.CREDENTIALS_JSON,
            SecretStore.ACCOUNT_PROFILE,
            SecretStore.AUTH_STATUS,
            SecretStore.ENV_VARS,
            SecretStore.SETTINGS_JSON,
        ).filter { inTheSafe(it) != null }

        assertTrue(
            "these entries are in the store every test class in this JVM shares, so some test wrote them " +
                "without installing a store of its own: $leaked",
            leaked.isEmpty(),
        )
    }

    fun `test a provider API key rides the same seam`() {
        val settings = ClaudeSettings.getInstance(project)
        SecretStore.storeOverride = mutableMapOf()
        settings.setProviderApiKey(Provider.ANTHROPIC, "sk-ant-from-a-test")

        assertEquals("sk-ant-from-a-test", settings.getProviderApiKey(Provider.ANTHROPIC))
        assertFixtureSafe()
        assertNull(
            "a provider key written by a test must not reach the PasswordSafe",
            PasswordSafe.instance.getPassword(
                CredentialAttributes(generateServiceName("ClaudeCodeNative", "providerApiKey:anthropic")),
            ),
        )

        SecretStore.storeOverride = null
        assertEquals("", settings.getProviderApiKey(Provider.ANTHROPIC))
    }
}
