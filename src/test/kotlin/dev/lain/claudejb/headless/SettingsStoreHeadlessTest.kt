package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore

class SettingsStoreHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
    }

    override fun tearDown() {
        try {
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    fun `test every state field survives a round trip through the safe`() {
        val saved = ClaudeSettings.State().apply {
            model = "claude-opus-5[1m]"
            permissionMode = "acceptEdits"
            maxTurns = 7
            maxBudgetUsd = 12.5
            addDirs = "/tmp/a\n/tmp/b"
            strictMcpConfig = true
            disabledSecurityRules = "WSL_MOUNT"
            envVars = "FOO=bar\nTOKEN=shhh"
        }
        SettingsStore.save(saved)
        val loaded = SettingsStore.load()
        assertEquals("claude-opus-5[1m]", loaded.model)
        assertEquals("acceptEdits", loaded.permissionMode)
        assertEquals(7, loaded.maxTurns)
        assertEquals(12.5, loaded.maxBudgetUsd)
        assertEquals("/tmp/a\n/tmp/b", loaded.addDirs)
        assertTrue(loaded.strictMcpConfig)
        assertEquals("WSL_MOUNT", loaded.disabledSecurityRules)
        assertEquals("FOO=bar\nTOKEN=shhh", loaded.envVars)
    }

    fun `test no state field is silently dropped`() {
        val fields = ClaudeSettings.State::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("$") }
        SettingsStore.save(ClaudeSettings.State())
        val stored = SecretStore.get(SecretStore.SETTINGS_JSON).orEmpty()
        val missing = fields.filterNot { stored.contains("\"$it\"") }
        assertTrue("these settings are never persisted: $missing", missing.isEmpty())
    }

    fun `test the defaults are Opus, ask each time, high effort`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val fresh = SettingsStore.load()
        assertEquals(dev.lain.claudejb.session.ClaudeSession.DEFAULT_MODEL, fresh.model)
        assertEquals("opus[1m]", fresh.model)
        assertEquals("default", fresh.permissionMode)
        assertEquals("high", fresh.effort)
    }

    fun `test an unknown key from a newer version does not break an older one`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"x","somethingFromTheFuture":{"a":1}}""")
        assertEquals("x", SettingsStore.load().model)
    }

    fun `test an existing configuration is never overwritten by a legacy one`() {
        SettingsStore.save(ClaudeSettings.State().apply { model = "the-one-in-use" })
        assertFalse(
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
        assertEquals("the-one-in-use", SettingsStore.load().model)
    }

    fun `test a legacy state carrying nothing is not a migration`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(SettingsStore.migrateFrom(ClaudeSettings.State()))
        assertNull(SecretStore.get(SecretStore.SETTINGS_JSON))
    }

    fun `test a failed read refuses the next save`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, "this is not a settings document")
        assertEquals(ClaudeSettings.State().model, SettingsStore.load().model)
        assertFalse(
            "a save after a failed read must be refused",
            SettingsStore.save(ClaudeSettings.State().apply { model = "defaults-must-not-win" }),
        )
        assertEquals("this is not a settings document", SecretStore.get(SecretStore.SETTINGS_JSON))
    }

    fun `test a later successful read lifts the veto`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, "this is not a settings document")
        SettingsStore.load()
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        SettingsStore.load()
        assertTrue(SettingsStore.save(ClaudeSettings.State().apply { model = "saved-again" }))
        assertEquals("saved-again", SettingsStore.load().model)
    }

    fun `test a legacy state that carries something is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(
                ClaudeSettings.State().apply {
                    model = "from-the-old-file"
                    claudePath = "/usr/bin/claude"
                },
            ),
        )
        assertEquals("from-the-old-file", SettingsStore.load().model)
    }

    fun `test a legacy permission mode weaker than the default is not adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val legacy = ClaudeSettings.State().apply {
            model = "from-the-old-file"
            permissionMode = "bypassPermissions"
            allowedTools = "Bash"
        }

        assertTrue(SettingsStore.migrateFrom(legacy))

        val loaded = SettingsStore.load()
        assertEquals("default", loaded.permissionMode)
        assertEquals("from-the-old-file", loaded.model)
        assertEquals("Bash", loaded.allowedTools)
        assertEquals("bypassPermissions", legacy.permissionMode)
    }

    fun `test a legacy permission mode nobody recognises is not adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(
                ClaudeSettings.State().apply {
                    model = "from-the-old-file"
                    permissionMode = "something-a-newer-binary-might-take"
                },
            ),
        )
        assertEquals("default", SettingsStore.load().permissionMode)
    }

    fun `test a legacy plan mode is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { permissionMode = "plan" }),
        )
        assertEquals("plan", SettingsStore.load().permissionMode)
    }

    fun `test a legacy file carrying only a weakened mode migrates nothing at all`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(
            "a file whose only content is a refused mode carries nothing and must not migrate",
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { permissionMode = "bypassPermissions" }),
        )
        assertNull("nothing was adopted, so no document may exist", SecretStore.get(SecretStore.SETTINGS_JSON))
    }
}
