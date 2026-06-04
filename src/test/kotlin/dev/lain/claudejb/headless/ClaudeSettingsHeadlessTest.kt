package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import kotlinx.serialization.json.JsonObject

/** Headless: the [ClaudeSettings] project service holds launch defaults and the "Always allow" tool set. */
class ClaudeSettingsHeadlessTest : BasePlatformTestCase() {

    private val settings get() = ClaudeSettings.getInstance(project)
    private val emptyInput = JsonObject(emptyMap())

    override fun setUp() {
        super.setUp()
        // The light-fixture project service is reused across methods; restore the defaults under test.
        settings.loadState(ClaudeSettings.State())
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(settings)
        assertSame(settings, ClaudeSettings.getInstance(project))
    }

    fun `test defaults are correct`() {
        assertEquals("default", settings.state.model)
        assertTrue(settings.restoreOpenChatsOnStartup)
        assertTrue(settings.state.restoreOpenChatsOnStartup)
    }

    fun `test state mutation survives getState loadState round-trip`() {
        settings.state.model = "sonnet"
        val saved = settings.getState()
        val reloaded = ClaudeSettings()
        reloaded.loadState(saved)
        assertEquals("sonnet", reloaded.state.model)
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
        settings.rememberToolAlwaysAllow("Bash")
        assertTrue(settings.isToolAlwaysAllowed("Bash", emptyInput))
        assertTrue("Bash" in settings.alwaysAllowedTools())
        settings.forgetToolAlwaysAllow("Bash")
        assertFalse(settings.isToolAlwaysAllowed("Bash", emptyInput))
        assertFalse("Bash" in settings.alwaysAllowedTools())
    }

    fun `test rememberToolAlwaysAllow is idempotent`() {
        settings.rememberToolAlwaysAllow("Edit")
        settings.rememberToolAlwaysAllow("Edit")
        assertEquals(listOf("Edit"), settings.alwaysAllowedTools())
    }
}
