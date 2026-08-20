package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope
import dev.lain.claudejb.settings.SettingsStore

class SettingsStoreHeadlessTest : BasePlatformTestCase() {

    private val scope = SettingsScope("scope-under-test")
    private val other = SettingsScope("a-different-project")

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load(scope)
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
        SettingsStore.save(scope, saved)
        val loaded = SettingsStore.load(scope)
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
        SettingsStore.save(scope, ClaudeSettings.State())
        val stored = SecretStore.get(scope.secretName).orEmpty()
        val missing = fields.filterNot { stored.contains("\"$it\"") }
        assertTrue("these settings are never persisted: $missing", missing.isEmpty())
    }

    fun `test one project's settings are not another's`() {
        SettingsStore.save(scope, ClaudeSettings.State().apply { model = "mine" })
        SettingsStore.save(other, ClaudeSettings.State().apply { model = "theirs" })

        assertEquals("mine", SettingsStore.load(scope).model)
        assertEquals("theirs", SettingsStore.load(other).model)
    }

    fun `test a scope with nothing of its own inherits the shared document`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"what-every-version-up-to-5-5-shared"}""")

        assertEquals("what-every-version-up-to-5-5-shared", SettingsStore.load(scope).model)
        assertEquals(
            "the seed has to reach a second project too, not only the first one opened",
            "what-every-version-up-to-5-5-shared",
            SettingsStore.load(other).model,
        )
    }

    fun `test inheriting never consumes the shared document`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"the-seed"}""")
        SettingsStore.load(scope)
        SettingsStore.save(scope, ClaudeSettings.State().apply { model = "diverged" })

        assertNotNull(
            "deleting the seed would silently empty every project opened afterwards",
            SecretStore.get(SecretStore.SETTINGS_JSON),
        )
        assertEquals("diverged", SettingsStore.load(scope).model)
        assertEquals("the-seed", SettingsStore.load(other).model)
    }

    fun `test a scope's own document wins over the shared one`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"the-seed"}""")
        SettingsStore.save(scope, ClaudeSettings.State().apply { model = "mine" })

        assertEquals("mine", SettingsStore.load(scope).model)
    }

    fun `test a pre-5-6 signedOut is lifted out of the document into its own entry`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"x","signedOut":true}""")

        SettingsStore.load(scope)

        assertEquals(
            "being signed out is a credential fact, so it must stop being per project",
            true.toString(),
            SecretStore.get(SecretStore.SIGNED_OUT),
        )
    }

    fun `test a pre-5-6 document that was signed IN leaves the entry alone`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"x","signedOut":false}""")

        SettingsStore.load(scope)

        assertNull(SecretStore.get(SecretStore.SIGNED_OUT))
    }

    fun `test the defaults are Opus, ask each time, high effort, guard on`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val fresh = SettingsStore.load(scope)
        assertEquals(dev.lain.claudejb.session.ClaudeSession.DEFAULT_MODEL, fresh.model)
        assertEquals("opus[1m]", fresh.model)
        assertEquals("default", fresh.permissionMode)
        assertEquals("high", fresh.effort)
        assertEquals(
            "a fresh install is protected, with nothing to switch on",
            "enforcing",
            fresh.guardMode,
        )
        assertEquals(0L, fresh.guardDisabledUntil)
    }

    fun `test an unknown key from a newer version does not break an older one`() {
        SecretStore.set(scope.secretName, """{"model":"x","somethingFromTheFuture":{"a":1}}""")
        assertEquals("x", SettingsStore.load(scope).model)
    }

    fun `test an existing configuration is never overwritten by a legacy one`() {
        SettingsStore.save(scope, ClaudeSettings.State().apply { model = "the-one-in-use" })
        assertFalse(
            SettingsStore.migrateFrom(scope, ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
        assertEquals("the-one-in-use", SettingsStore.load(scope).model)
    }

    fun `test the shared document also outranks a legacy project file`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"newer-than-the-xml"}""")
        assertFalse(
            SettingsStore.migrateFrom(scope, ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
        assertEquals("newer-than-the-xml", SettingsStore.load(scope).model)
    }

    fun `test a legacy state carrying nothing is not a migration`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(SettingsStore.migrateFrom(scope, ClaudeSettings.State()))
        assertNull(SecretStore.get(scope.secretName))
    }

    fun `test a failed read refuses the next save`() {
        SecretStore.set(scope.secretName, "this is not a settings document")
        assertEquals(ClaudeSettings.State().model, SettingsStore.load(scope).model)
        assertFalse(
            "a save after a failed read must be refused",
            SettingsStore.save(scope, ClaudeSettings.State().apply { model = "defaults-must-not-win" }),
        )
        assertEquals("this is not a settings document", SecretStore.get(scope.secretName))
    }

    fun `test one scope's failed read does not veto another scope's save`() {
        SecretStore.set(scope.secretName, "this is not a settings document")
        SettingsStore.load(scope)

        assertTrue(
            "a keyring hiccup in one project must not freeze every other project's settings",
            SettingsStore.save(other, ClaudeSettings.State().apply { model = "unaffected" }),
        )
    }

    fun `test a later successful read lifts the veto`() {
        SecretStore.set(scope.secretName, "this is not a settings document")
        SettingsStore.load(scope)
        SecretStore.clear(scope.secretName)
        SettingsStore.load(scope)
        assertTrue(SettingsStore.save(scope, ClaudeSettings.State().apply { model = "saved-again" }))
        assertEquals("saved-again", SettingsStore.load(scope).model)
    }

    fun `test a legacy state that carries something is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(
                scope,
                ClaudeSettings.State().apply {
                    model = "from-the-old-file"
                    claudePath = "/usr/bin/claude"
                },
            ),
        )
        assertEquals("from-the-old-file", SettingsStore.load(scope).model)
        assertNull("the .idea file belongs to ONE project, not to every project", SettingsStore.loadOrNull(other)?.claudePath?.ifBlank { null })
    }

    fun `test a legacy permission mode weaker than the default is not adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val legacy = ClaudeSettings.State().apply {
            model = "from-the-old-file"
            permissionMode = "bypassPermissions"
            allowedTools = "Bash"
        }

        assertTrue(SettingsStore.migrateFrom(scope, legacy))

        val loaded = SettingsStore.load(scope)
        assertEquals("default", loaded.permissionMode)
        assertEquals("from-the-old-file", loaded.model)
        assertEquals("Bash", loaded.allowedTools)
        assertEquals("bypassPermissions", legacy.permissionMode)
    }

    fun `test a legacy permission mode nobody recognises is not adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(
                scope,
                ClaudeSettings.State().apply {
                    model = "from-the-old-file"
                    permissionMode = "something-a-newer-binary-might-take"
                },
            ),
        )
        assertEquals("default", SettingsStore.load(scope).permissionMode)
    }

    fun `test a legacy plan mode is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(scope, ClaudeSettings.State().apply { permissionMode = "plan" }),
        )
        assertEquals("plan", SettingsStore.load(scope).permissionMode)
    }

    fun `test a legacy file carrying only a weakened mode migrates nothing at all`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(
            "a file whose only content is a refused mode carries nothing and must not migrate",
            SettingsStore.migrateFrom(scope, ClaudeSettings.State().apply { permissionMode = "bypassPermissions" }),
        )
        assertNull("nothing was adopted, so no document may exist", SecretStore.get(scope.secretName))
    }
}
