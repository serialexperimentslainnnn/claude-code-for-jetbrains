package dev.lain.claudejb.headless

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStoreTestAccess

/**
 * A test cannot see, or leave behind, another test's secrets.
 *
 * **The defect this pins, measured rather than assumed.** The IDE's PasswordSafe is an APPLICATION service
 * and the platform test framework reuses one Application for the whole run; the `test` task sets neither
 * `maxParallelForks` nor `forkEvery`, so every class in the suite shared one store. Running
 * `ClaudeSettingsConfigurableHeadlessTest` followed by a probe class showed its fixture document still there
 * — `"model": "opus-pinned-by-the-user", "permissionMode": "acceptEdits"` — for the next class to read. A
 * test asserting on a value it never wrote is the visible half; a test running under a permission mode
 * nobody chose is the half nobody would have noticed.
 *
 * **What this is NOT.** It is not what keeps the developer's OS keychain safe: a test JVM already cannot
 * reach that, by the platform's own doing (`testServiceImplementation="TestPasswordSafeImpl"` in
 * `credential-store.xml`, and `computeProvider` returning an `InMemoryCredentialStore` whenever
 * `isUnitTestMode`, before any native store is constructed). Verified live on the pinned build: inside this
 * fixture `PasswordSafe.instance` is `TestPasswordSafeImpl` over `InMemoryCredentialStore`, and the
 * machine's real `CLAUDE_SETTINGS_JSON` and `CLAUDE_CREDENTIALS_JSON` read back as null. The seam below
 * still refuses to touch the safe at all in a test JVM, which is cheap and means that property stops being
 * something we inherit silently from a platform build we widen `untilBuild` against every release.
 *
 * Everything here that talks to `PasswordSafe` directly does so under [assertFixtureSafe] and only ever
 * under [SecretStore.OAUTH_TOKEN] — a slot the sign-in card writes only after a real login, so even a
 * mutation of the seam cannot destroy a credential that exists.
 */
class SecretStoreIsolationHeadlessTest : BasePlatformTestCase() {

    /** Deliberately no store installed by default: half of what is pinned here is the no-store behaviour. */
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

    /**
     * With no store installed, a test JVM does not reach the PasswordSafe at all — not to read, not to write.
     *
     * This is the fail-closed half. Falling back to the shared application store is what made two unrelated
     * test classes correlate, so "nobody installed one" resolves to nothing rather than to everybody's.
     */
    fun `test a test JVM with no store installed cannot reach the PasswordSafe`() {
        SecretStore.storeOverride = null
        assertTrue("precondition: the store must be inert here", SecretStore.inert())

        SecretStore.set(SecretStore.OAUTH_TOKEN, "must-not-reach-the-safe")

        assertNull("an inert store must not write through to the PasswordSafe", inTheSafe(SecretStore.OAUTH_TOKEN))
        assertNull("an inert store must not read through either", SecretStore.get(SecretStore.OAUTH_TOKEN))
        assertTrue(SecretStore.envOverlay(emptySet()).isEmpty())
    }

    /** An installed store round-trips, stays out of the PasswordSafe, and dies with the test that installed it. */
    fun `test an installed store isolates its entries and does not outlive the test`() {
        SecretStore.storeOverride = mutableMapOf()
        SecretStore.set(SecretStore.OAUTH_TOKEN, "isolated")

        assertEquals("isolated", SecretStore.get(SecretStore.OAUTH_TOKEN))
        assertNull("an installed store must not write through either", inTheSafe(SecretStore.OAUTH_TOKEN))

        SecretStore.storeOverride = null
        assertNull("the values must die with the store", SecretStore.get(SecretStore.OAUTH_TOKEN))
    }

    /** Two tests, two stores: what one wrote is not what the other reads. This IS the reported failure. */
    fun `test one test cannot see another test's values`() {
        SecretStore.storeOverride = mutableMapOf()
        SettingsStoreTestAccess.save(ClaudeSettings.State().apply { model = "written-by-the-first-test" })
        assertEquals("written-by-the-first-test", SettingsStoreTestAccess.load().model)

        SecretStore.storeOverride = mutableMapOf() // …the next test starts
        assertEquals(
            "a fresh store must not carry the previous test's configuration",
            ClaudeSettings.State().model,
            SettingsStoreTestAccess.load().model,
        )
    }

    /**
     * A store that is not there is NOT a failed read, and must not veto the next save.
     *
     * `readFailed` guards a configuration we could not read but which exists; there is none behind an inert
     * store. It also lives on an `object`, so setting it here would outlive this call and refuse the saves of
     * every later test in the JVM — one inert read poisoning a suite, the same shape as the bug the flag
     * exists to prevent.
     */
    fun `test an inert store is not a failed read`() {
        SecretStore.storeOverride = null
        SettingsStoreTestAccess.load() // the inert read

        SecretStore.storeOverride = mutableMapOf()
        assertTrue(
            "an inert read must not veto the next save",
            SettingsStoreTestAccess.save(ClaudeSettings.State().apply { model = "saved-after-an-inert-read" }),
        )
        assertEquals("saved-after-an-inert-read", SettingsStoreTestAccess.load().model)
    }

    /**
     * …and the other side of it: an inert store refuses to save rather than reporting a success nothing kept.
     *
     * That is what makes forgetting to install one LOUD. Reporting success would let a test round-trip
     * through a store that is not there and pass without ever exercising the thing it names.
     */
    fun `test an inert store refuses to save rather than reporting a success nothing kept`() {
        SecretStore.storeOverride = null

        assertFalse(SettingsStoreTestAccess.save(ClaudeSettings.State().apply { model = "nowhere-to-go" }))
        assertFalse(
            "a migration into a store that is not there has not migrated anything",
            SettingsStoreTestAccess.migrateFrom(ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
    }

    /**
     * Nothing in this suite may leave anything in the Application-wide PasswordSafe. Ever.
     *
     * This is the regression test for the defect itself, stated where it can be observed: the leak was only
     * ever visible from OUTSIDE the class that caused it, because a test that writes to a shared store sees
     * exactly what it expects. So this asserts the shared store is empty of everything the plugin owns.
     *
     * **Its power depends on when it runs, and that is worth being honest about.** Run before a leaking
     * class it proves nothing; run after one it names the entry. JUnit gives no ordering guarantee, so it is
     * a net rather than a proof — but the invariant it asserts is true at every instant now, so it can only
     * ever fail for the right reason, and across a whole suite it will land after the offender often enough
     * to matter. The deterministic half is the source scan in `SecretStoreIsolationContractTest`.
     */
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

    /**
     * The provider API keys ride the same seam.
     *
     * They were the only other door onto `PasswordSafe` in the plugin, under their own service name, and a
     * door that skips the seam is a door a test leaks through — one holding a real third-party API key.
     */
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
