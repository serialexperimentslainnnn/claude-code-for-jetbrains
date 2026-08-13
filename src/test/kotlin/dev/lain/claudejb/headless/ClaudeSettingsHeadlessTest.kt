package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStoreTestAccess
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
     * read what a different class wrote. The [SettingsStoreTestAccess.load] is the other half: `readFailed`
     * lives on an `object` and would otherwise carry a previous test's veto into the saves below.
     */
    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStoreTestAccess.load()
        // The light-fixture project service is reused across methods; restore the defaults under test.
        settings.replaceState(ClaudeSettings.State())
    }

    override fun tearDown() {
        try {
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
        // Security toggles (Settings ▸ Claude Code ▸ Security) all default ON — a fresh install reproduces the
        // original hard lock exactly; the user must explicitly soften a rule.
        assertTrue(settings.state.securityBlockCredentials)
        assertTrue(settings.state.securityBlockDangerousCommands)
        assertTrue(settings.state.securityBlockForeignOtherUserHome)
        assertTrue(settings.state.securityBlockForeignNetworkMounts)
        assertTrue(settings.state.securityBlockForeignWslMounts)
    }

    fun `test sensitivePolicy wires the security toggles through`() {
        settings.state.securityBlockCredentials = false
        settings.state.securityBlockForeignWslMounts = false
        val policy = settings.sensitivePolicy(projectRoot = null)
        assertFalse(policy.enforceCredentials)
        assertFalse(policy.enforceForeignWslMounts)
        // Untouched toggles stay at their default.
        assertTrue(policy.enforceDangerousCommands)
        assertTrue(policy.enforceForeignOtherUserHome)
        assertTrue(policy.enforceForeignNetworkMounts)
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
        assertTrue("Write" in SettingsStoreTestAccess.load().alwaysAllowTools)
    }
}
