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

/** Headless: the [ClaudeSettings] project service holds launch defaults and the "Always allow" tool set. */
class ClaudeSettingsHeadlessTest : BasePlatformTestCase() {

    private val settings get() = ClaudeSettings.getInstance(project)
    private val emptyInput = JsonObject(emptyMap())

    /**
     * A store of this method's own, then defaults on top of it.
     *
     * The fixture's PasswordSafe belongs to an Application the platform reuses for the whole run, so without
     * [SecretStore.storeOverride] one document is shared by every test class in the JVM and a method here can
     * read what a different class wrote. The [SettingsStore.load] is the other half: `readFailed`
     * lives on an `object` and would otherwise carry a previous test's veto into the saves below.
     */
    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
        // The light-fixture project service is reused across methods; restore the defaults under test.
        settings.replaceState(ClaudeSettings.State())
    }

    /**
     * The store outlives this method unless the queued writes have run against it.
     *
     * [ClaudeSettings.update] persists on a background queue, so dropping [SecretStore.storeOverride] while a
     * write is still in flight would let it land in whatever store the NEXT test installs. Draining first is
     * also what makes every assertion below deterministic without a single sleep: the queue is FIFO.
     */
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
        // Fresh installs pin the concrete Opus tier, not the binary's floating "default" alias.
        assertEquals(ClaudeSession.DEFAULT_MODEL, settings.state.model)
        assertEquals("opus[1m]", settings.state.model)
        assertTrue(settings.restoreOpenChatsOnStartup)
        assertTrue(settings.state.restoreOpenChatsOnStartup)
        // Nothing is disabled on a fresh install, which IS the original hard lock: the stored value is the set of
        // rules the user switched off, so "every rule enforced" is the empty string rather than N booleans.
        assertEquals("", settings.state.disabledSecurityRules)
        assertEquals("", settings.state.securityExtraBlockedDomains)
    }

    fun `test sensitivePolicy wires the disabled rules through`() {
        settings.state.disabledSecurityRules = "CREDENTIALS,WSL_MOUNT"
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertEquals(setOf(SecurityRule.CREDENTIALS, SecurityRule.WSL_MOUNT), policy.disabledRules)
        // Everything not named stays enforced, including rules that did not exist when this was seven booleans.
        assertFalse(SecurityRule.SHELL_FILE_WRITE in policy.disabledRules)
        assertFalse(SecurityRule.BLOCKED_DOMAIN in policy.disabledRules)
    }

    fun `test an unresolvable rule id is dropped rather than guessed at`() {
        // The failure direction that makes the disabled set the right thing to store: a stale or garbled id can
        // only ever fail to turn a rule OFF.
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
        // The persistence itself is covered by SettingsStoreCoverageTest, which points the home at a temp
        // directory. Here the object contract is enough: what you put in is what the rest of the plugin reads.
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

    /** The mutation and the write are one operation: a remembered tool must survive a restart. */
    fun `test remembering a tool persists`() {
        settings.alwaysAllow.remember("Write")
        ClaudeSettings.awaitWrites()
        assertTrue("Write" in SettingsStore.load().alwaysAllowTools)
    }

    /**
     * REGRESSION: a setting changed in one IDE is not lost when another IDE changes a different one.
     *
     * The safe is application-wide, so two IDEs open on this machine share ONE settings document, while each
     * holds its own in-memory copy that is loaded once and never invalidated. A save built from that copy
     * carries the copy's value for every field the other IDE has changed since, and replaces them — with two
     * IDEs open, whoever writes last wins the whole document. [ClaudeSettings.update] therefore re-reads the
     * stored document and applies the delta to THAT, which narrows what any one mutation can overwrite to the
     * field it actually touches.
     *
     * The other IDE is simulated by writing straight to the store, which is exactly what it is from this
     * process's point of view: a document that changed underneath it, with nothing to notify anyone.
     */
    fun `test an update does not overwrite what another IDE stored`() {
        settings.update { it.model = "chosen-in-this-ide" }
        ClaudeSettings.awaitWrites()

        // The other IDE: it reads the shared document, changes a field of its own, writes it back.
        val elsewhere = SettingsStore.load().apply { effort = "low" }
        assertTrue("the fixture store must accept the write", SettingsStore.save(elsewhere))

        // This IDE changes a third field. Its in-memory copy has never seen `effort`.
        settings.update { it.permissionMode = "plan" }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load()
        assertEquals("this IDE's own earlier change was lost", "chosen-in-this-ide", stored.model)
        assertEquals("the other IDE's change was overwritten", "low", stored.effort)
        assertEquals("plan", stored.permissionMode)
    }

    /**
     * Mutations raised concurrently on ONE JVM all reach the store.
     *
     * The same read-modify-write that fixes two IDEs would create a new race inside one: two mutations reading
     * the same document and writing it back one after the other lose the first. The write queue is a single
     * thread and every entry point of [dev.lain.claudejb.settings.SettingsStore] holds its monitor, so a
     * mutation always reads a document that already contains every mutation before it.
     *
     * The assertion is on the STORED document deliberately. Each block here is `+=` — a read-modify-write in
     * its own right — so the in-memory copy the threads share can genuinely lose an append; what must not lose
     * one is the safe, because that is the configuration that comes back tomorrow. Real call sites assign
     * absolute values and do not have even that.
     */
    fun `test mutations raised from several threads all reach the store`() {
        // First read on this thread, so the threads below only ever mutate an already-loaded document.
        assertNotNull(settings.state)
        val threads = (1..8).map { n -> Thread { settings.update { it.envVars += "K$n=v$n\n" } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        ClaudeSettings.awaitWrites()

        val stored = SettingsStore.load()
        (1..8).forEach { n -> assertTrue("K$n=v$n was lost", "K$n=v$n" in stored.envVars) }
    }

    /**
     * A read that FAILED abandons the write; it never writes a half-reconstructed document.
     *
     * Reading is now part of every write, which makes a transient safe far more dangerous than it was:
     * `load` falls back to defaults when it cannot reach the store, and applying a delta to those defaults
     * produces a complete, plausible document that is not the user's configuration. A dropped mutation is
     * recoverable by making it again; a wiped configuration is not.
     *
     * The accepted cost, stated rather than hidden: the in-memory copy still takes the change, so this session
     * runs with a setting the safe refused, and the next restart reads the old one back. That is the right way
     * round — the alternative is a value that reverts under the user mid-session — and it is why the refusal is
     * logged.
     */
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

    /** A store whose reads throw — a locked keyring, as the plugin experiences it. Writes still work. */
    private class UnreadableStore(backing: MutableMap<String, String>) :
        MutableMap<String, String> by backing {
        override fun get(key: String): String? = error("the credential store cannot be read")
    }
}
