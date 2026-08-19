package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore
import dev.lain.claudejb.settings.parseEnv
import dev.lain.claudejb.settings.sensitivePolicy
import kotlinx.serialization.json.JsonObject

class ClaudeSettingsHeadlessTest : BasePlatformTestCase() {

    private val settings get() = ClaudeSettings.getInstance(project)
    private val emptyInput = JsonObject(emptyMap())

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
        settings.replaceState(ClaudeSettings.State())
    }

    override fun tearDown() {
        try {
            ClaudeSettings.awaitWrites()
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(settings)
        assertSame(settings, ClaudeSettings.getInstance(project))
    }

    fun `test defaults are correct`() {
        assertEquals(ClaudeSession.DEFAULT_MODEL, settings.state.model)
        assertEquals("opus[1m]", settings.state.model)
        assertTrue(settings.restoreOpenChatsOnStartup)
        assertTrue(settings.state.restoreOpenChatsOnStartup)
        assertEquals("", settings.state.disabledSecurityRules)
        assertEquals("", settings.state.securityExtraBlockedDomains)
    }

    fun `test sensitivePolicy wires the disabled rules through`() {
        settings.state.disabledSecurityRules = "CREDENTIALS,WSL_MOUNT"
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(setOf(SecurityRule.CREDENTIALS, SecurityRule.WSL_MOUNT), policy.disabledRules)
        assertFalse(SecurityRule.SHELL_FILE_WRITE in policy.disabledRules)
        assertFalse(SecurityRule.BLOCKED_DOMAIN in policy.disabledRules)
    }

    fun `test an unresolvable rule id is dropped rather than guessed at`() {
        settings.state.disabledSecurityRules = "credentials,NOT_A_RULE,TEMP_DIR"
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(setOf(SecurityRule.TEMP_DIR), policy.disabledRules)
    }

    fun `test the extra blocked domains reach the policy, comments and blanks dropped`() {
        settings.state.securityExtraBlockedDomains = "# mine\npaste.example.com\n\n  drop.example.net  "
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(listOf("paste.example.com", "drop.example.net"), policy.extraBlockedDomains)
    }

    fun `test a replaced state is what the settings then report`() {
        settings.replaceState(ClaudeSettings.State().apply { model = "sonnet" })
        assertEquals("sonnet", settings.state.model)
    }

    fun `test parseEnv reads KEY VALUE lines`() {
        settings.state.envVars = "FOO=bar\n# comment\n\nBAZ=qux"
        val env = settings.parseEnv()
        assertEquals("bar", env["FOO"])
        assertEquals("qux", env["BAZ"])
        assertEquals(2, env.size)
    }

    fun `test remember and forget always-allow tool`() {
        assertFalse(settings.isToolAlwaysAllowed("Bash", emptyInput))
        settings.alwaysAllow.remember("Bash")
        assertTrue(settings.isToolAlwaysAllowed("Bash", emptyInput))
        assertTrue("Bash" in settings.alwaysAllow.all())
        settings.alwaysAllow.forget("Bash")
        assertFalse(settings.isToolAlwaysAllowed("Bash", emptyInput))
        assertFalse("Bash" in settings.alwaysAllow.all())
    }

    fun `test remembering a tool is idempotent`() {
        settings.alwaysAllow.remember("Edit")
        settings.alwaysAllow.remember("Edit")
        assertEquals(listOf("Edit"), settings.alwaysAllow.all())
    }

    fun `test remembering a tool persists`() {
        settings.alwaysAllow.remember("Write")
        ClaudeSettings.awaitWrites()
        assertTrue("Write" in SettingsStore.load().alwaysAllowTools)
    }

    fun `test an update does not overwrite what another IDE stored`() {
        settings.update { it.model = "chosen-in-this-ide" }
        ClaudeSettings.awaitWrites()

        val elsewhere = SettingsStore.load().apply { effort = "low" }
        assertTrue("the fixture store must accept the write", SettingsStore.save(elsewhere))

        settings.update { it.permissionMode = "plan" }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load()
        assertEquals("this IDE's own earlier change was lost", "chosen-in-this-ide", stored.model)
        assertEquals("the other IDE's change was overwritten", "low", stored.effort)
        assertEquals("plan", stored.permissionMode)
    }

    fun `test mutations raised from several threads all reach the store`() {
        assertNotNull(settings.state)
        val threads = (1..8).map { n -> Thread { settings.update { it.envVars += "K$n=v$n\n" } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load()
        (1..8).forEach { n -> assertTrue("K$n=v$n was lost", "K$n=v$n" in stored.envVars) }
    }

    fun `test a failed read abandons the write instead of replacing the configuration`() {
        val backing = mutableMapOf<String, String>()
        SecretStore.storeOverride = backing
        settings.update { it.model = "the-real-configuration" }
        ClaudeSettings.awaitWrites()
        val before = backing.toMap()
        assertTrue("nothing was stored, so the test would prove nothing", before.isNotEmpty())

        SecretStore.storeOverride = UnreadableStore(backing)
        settings.update { it.model = "must-not-land" }
        ClaudeSettings.awaitWrites()
        SecretStore.storeOverride = backing

        assertEquals("a failed read must produce no write at all", before, backing.toMap())
        assertEquals("the-real-configuration", SettingsStore.load().model)
    }

    private class UnreadableStore(backing: MutableMap<String, String>) :
        MutableMap<String, String> by backing {
        override fun get(key: String): String? = error("the credential store cannot be read")
    }
}
