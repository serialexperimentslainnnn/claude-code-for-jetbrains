package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SecuritySuspensions
import dev.lain.claudejb.settings.SettingsStore
import dev.lain.claudejb.settings.guardSuspended
import dev.lain.claudejb.settings.parseEnv
import dev.lain.claudejb.settings.sensitiveDecision
import dev.lain.claudejb.settings.sensitivePolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ClaudeSettingsHeadlessTest : BasePlatformTestCase() {

    private val settings get() = ClaudeSettings.getInstance(project)
    private val emptyInput = JsonObject(emptyMap())

    private val credentialRead = JsonObject(
        mapOf("command" to JsonPrimitive("cat ${System.getProperty("user.home")}/.ssh/id_rsa")),
    )

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load(settings.scope)
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
        assertEquals("the Sensitive Guard enforces out of the box", GuardMode.ENFORCING.wire, settings.state.guardMode)
        assertFalse("and nothing is suspending it", settings.guardSuspended())
    }

    fun `test the master switch is what makes the guard stop answering`() {
        assertEquals(
            SensitiveGuard.Verdict.DENY,
            settings.sensitiveDecision(credentialRead, projectRoot = null).verdict,
        )

        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.MINUTES_5, System.currentTimeMillis()) }

        assertEquals(
            "with the shield down nothing is judged at all — that is the whole point of it",
            SensitiveGuard.Verdict.ALLOW,
            settings.sensitiveDecision(credentialRead, projectRoot = null).verdict,
        )

        settings.update { SecuritySuspensions.guardOn(settings.scope.id, it) }

        assertEquals(
            SensitiveGuard.Verdict.DENY,
            settings.sensitiveDecision(credentialRead, projectRoot = null).verdict,
        )
    }

    fun `test Allow All still says which rule it let past`() {
        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.HOURS_4, System.currentTimeMillis()) }

        val decision = settings.sensitiveDecision(credentialRead, projectRoot = null)

        assertEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
        assertEquals(
            "a bypass nobody can see is a bypass nobody can undo",
            SecurityRule.CREDENTIALS,
            decision.rule,
        )
        assertTrue("the transcript row needs the why, not only the what", decision.reason.orEmpty().isNotBlank())
    }

    fun `test an ordinary call carries no rule and so warns about nothing`() {
        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.HOURS_4, System.currentTimeMillis()) }
        val harmless = kotlinx.serialization.json.JsonObject(
            mapOf("command" to JsonPrimitive("git status")),
        )

        val decision = settings.sensitiveDecision(harmless, projectRoot = null)

        assertEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
        assertNull("ordinary work must not be narrated as a bypass", decision.rule)
    }

    fun `test the guard in Permissive mode asks instead of refusing, whatever the rules say`() {
        assertEquals(
            SensitiveGuard.Verdict.DENY,
            settings.sensitiveDecision(credentialRead, projectRoot = null).verdict,
        )

        settings.update { it.guardMode = GuardMode.PERMISSIVE.wire }

        assertEquals(
            "Permissive is a card, never a silent allow",
            SensitiveGuard.Verdict.ASK,
            settings.sensitiveDecision(credentialRead, projectRoot = null).verdict,
        )
        assertEquals(SecurityRule.entries.toSet(), settings.sensitivePolicy(projectRoot = null).permissiveRules)
    }

    fun `test one rule set to Permissive leaves every other rule Enforcing`() {
        settings.update { it.disabledSecurityRules = SecurityRule.CREDENTIALS.name }

        val policy = settings.sensitivePolicy(projectRoot = null)

        assertEquals(setOf(SecurityRule.CREDENTIALS), policy.permissiveRules)
    }

    fun `test switching it back on clears all three stores at once`() {
        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.UNTIL_IDE_CLOSES, System.currentTimeMillis()) }
        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.FOREVER, System.currentTimeMillis()) }
        settings.update { SecuritySuspensions.guardOff(settings.scope.id, it, SecuritySuspensions.Duration.HOURS_8, System.currentTimeMillis()) }

        settings.update { SecuritySuspensions.guardOn(settings.scope.id, it) }

        assertFalse("one store outliving the others is how a switch lies", settings.guardSuspended())
    }

    fun `test sensitivePolicy wires the disabled rules through`() {
        settings.state.disabledSecurityRules = "CREDENTIALS,WSL_MOUNT"
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(setOf(SecurityRule.CREDENTIALS, SecurityRule.WSL_MOUNT), policy.permissiveRules)
        assertFalse(SecurityRule.SHELL_FILE_WRITE in policy.permissiveRules)
        assertFalse(SecurityRule.BLOCKED_DOMAIN in policy.permissiveRules)
    }

    fun `test an unresolvable rule id is dropped rather than guessed at`() {
        settings.state.disabledSecurityRules = "credentials,NOT_A_RULE,TEMP_DIR"
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(setOf(SecurityRule.TEMP_DIR), policy.permissiveRules)
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
        assertTrue("Write" in SettingsStore.load(settings.scope).alwaysAllowTools)
    }

    fun `test an update does not overwrite what another window stored`() {
        settings.update { it.model = "chosen-in-this-window" }
        ClaudeSettings.awaitWrites()

        val elsewhere = SettingsStore.load(settings.scope).apply { effort = "low" }
        assertTrue("the fixture store must accept the write", SettingsStore.save(settings.scope, elsewhere))

        settings.update { it.permissionMode = "plan" }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load(settings.scope)
        assertEquals("this window's own earlier change was lost", "chosen-in-this-window", stored.model)
        assertEquals("the other window's change was overwritten", "low", stored.effort)
        assertEquals("plan", stored.permissionMode)
    }

    fun `test mutations raised from several threads all reach the store`() {
        assertNotNull(settings.state)
        val threads = (1..8).map { n -> Thread { settings.update { it.envVars += "K$n=v$n\n" } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load(settings.scope)
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
        assertEquals("the-real-configuration", SettingsStore.load(settings.scope).model)
    }

    private class UnreadableStore(backing: MutableMap<String, String>) :
        MutableMap<String, String> by backing {
        override fun get(key: String): String? = error("the credential store cannot be read")
    }
}
