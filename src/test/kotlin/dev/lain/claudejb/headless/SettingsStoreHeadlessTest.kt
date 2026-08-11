package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStoreTestAccess

/**
 * The settings persist into the IDE's PasswordSafe — the OS credential store — and nowhere else.
 *
 * **Headless rather than plain-JVM, and that is the point.** The store talks to `PasswordSafe.instance`,
 * which only exists inside a platform fixture. The version of these tests that ran on a bare JVM wrote to
 * `~/.claude/ide/claude-code-native/settings.json` — the DEVELOPER'S OWN configuration — because nothing
 * stopped it: a real install was found holding `some-unlisted-model` and, before that, `haiku`, both of them
 * literals out of these very tests. It read as the plugin corrupting itself on reinstall, since the
 * reinstall always followed a `./gradlew test`.
 *
 * Two rules come out of that and are pinned below: the settings live in the safe, and a read that FAILS is
 * not an empty configuration.
 */
class SettingsStoreHeadlessTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SecretStore.clear(SecretStore.SETTINGS_JSON)
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
            securityBlockForeignWslMounts = false
            envVars = "FOO=bar\nTOKEN=shhh"
        }
        SettingsStoreTestAccess.save(saved)
        val loaded = SettingsStoreTestAccess.load()
        assertEquals("claude-opus-5[1m]", loaded.model)
        assertEquals("acceptEdits", loaded.permissionMode)
        assertEquals(7, loaded.maxTurns)
        assertEquals(12.5, loaded.maxBudgetUsd)
        assertEquals("/tmp/a\n/tmp/b", loaded.addDirs)
        assertTrue(loaded.strictMcpConfig)
        assertFalse(loaded.securityBlockForeignWslMounts)
        // The env block rides inside the document now — the whole thing is encrypted at rest, so there is no
        // longer a reason to keep it in a second entry.
        assertEquals("FOO=bar\nTOKEN=shhh", loaded.envVars)
    }

    /**
     * Every field of [ClaudeSettings.State] is actually persisted — checked against the class, not against a
     * list someone maintains by hand.
     *
     * The hand-written serialiser missed nine fields on its first pass. A settings store that silently drops
     * one is worse than one that fails: it works until the next restart, and nothing says why it reverted.
     */
    fun `test no state field is silently dropped`() {
        // Instance fields only: a setting is an instance field. `@Serializable` adds a static `Companion`
        // (the generated serializer's holder), which is not a setting and has nothing to persist.
        val fields = ClaudeSettings.State::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("$") }
        SettingsStoreTestAccess.save(ClaudeSettings.State())
        val stored = SecretStore.get(SecretStore.SETTINGS_JSON).orEmpty()
        val missing = fields.filterNot { stored.contains("\"$it\"") }
        assertTrue("these settings are never persisted: $missing", missing.isEmpty())
    }

    /** With nothing stored, the plugin starts on the pinned tier, asking every time, thinking hard. */
    fun `test the defaults are Opus, ask each time, high effort`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val fresh = SettingsStoreTestAccess.load()
        assertEquals(dev.lain.claudejb.session.ClaudeSession.DEFAULT_MODEL, fresh.model)
        assertEquals("opus[1m]", fresh.model)
        assertEquals("default", fresh.permissionMode) // PermissionMode.DEFAULT = "Ask each time"
        assertEquals("high", fresh.effort)
    }

    fun `test an unknown key from a newer version does not break an older one`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"x","somethingFromTheFuture":{"a":1}}""")
        assertEquals("x", SettingsStoreTestAccess.load().model)
    }

    /** Once a configuration is stored it IS the configuration: a legacy project file cannot overwrite it. */
    fun `test an existing configuration is never overwritten by a legacy one`() {
        SettingsStoreTestAccess.save(ClaudeSettings.State().apply { model = "the-one-in-use" })
        assertFalse(
            SettingsStoreTestAccess.migrateFrom(ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
        assertEquals("the-one-in-use", SettingsStoreTestAccess.load().model)
    }

    /**
     * A project that carries NO settings must not create one out of factory values.
     *
     * The legacy component is declared with the old name and storage, so the platform hands it a state of
     * pure defaults when the project has no `claude-code.xml`. Adopting that looked exactly like a real
     * migration and marked the job done, so a genuine configuration sitting in another project's file was
     * never adopted — the plugin came up "reset" and nothing had failed.
     */
    fun `test a legacy state carrying nothing is not a migration`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(SettingsStoreTestAccess.migrateFrom(ClaudeSettings.State()))
        assertNull(SecretStore.get(SecretStore.SETTINGS_JSON))
    }

    fun `test a legacy state that carries something is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStoreTestAccess.migrateFrom(
                ClaudeSettings.State().apply {
                    model = "from-the-old-file"
                    claudePath = "/usr/bin/claude"
                },
            ),
        )
        assertEquals("from-the-old-file", SettingsStoreTestAccess.load().model)
    }
}
