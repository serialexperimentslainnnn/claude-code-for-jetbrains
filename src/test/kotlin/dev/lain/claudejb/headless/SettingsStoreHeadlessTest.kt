package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore

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
 *
 * **Every method gets a store of its own** ([SecretStore.storeOverride]). The fixture's PasswordSafe is an
 * `InMemoryCredentialStore` on an Application the platform reuses for the whole run, so without this the
 * suite has ONE settings document shared by every test class in the JVM — measured: the configurable's
 * fixture state (`model = opus-pinned-by-the-user`, `permissionMode = acceptEdits`) was still in there when
 * the next class started reading. That is how a test ends up asserting on a value it never wrote.
 */
class SettingsStoreHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        // `readFailed` is a flag on the store OBJECT, so it outlives the test that set it and would veto
        // every later save in this JVM. A clean read clears it — the same way the next IDE start would, and
        // it has to happen HERE because a method that saves before it loads would otherwise inherit the veto.
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
        SettingsStore.save(ClaudeSettings.State())
        val stored = SecretStore.get(SecretStore.SETTINGS_JSON).orEmpty()
        val missing = fields.filterNot { stored.contains("\"$it\"") }
        assertTrue("these settings are never persisted: $missing", missing.isEmpty())
    }

    /** With nothing stored, the plugin starts on the pinned tier, asking every time, thinking hard. */
    fun `test the defaults are Opus, ask each time, high effort`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        val fresh = SettingsStore.load()
        assertEquals(dev.lain.claudejb.session.ClaudeSession.DEFAULT_MODEL, fresh.model)
        assertEquals("opus[1m]", fresh.model)
        assertEquals("default", fresh.permissionMode) // PermissionMode.DEFAULT = "Ask each time"
        assertEquals("high", fresh.effort)
    }

    fun `test an unknown key from a newer version does not break an older one`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, """{"model":"x","somethingFromTheFuture":{"a":1}}""")
        assertEquals("x", SettingsStore.load().model)
    }

    /** Once a configuration is stored it IS the configuration: a legacy project file cannot overwrite it. */
    fun `test an existing configuration is never overwritten by a legacy one`() {
        SettingsStore.save(ClaudeSettings.State().apply { model = "the-one-in-use" })
        assertFalse(
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { model = "from-an-old-project" }),
        )
        assertEquals("the-one-in-use", SettingsStore.load().model)
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
        assertFalse(SettingsStore.migrateFrom(ClaudeSettings.State()))
        assertNull(SecretStore.get(SecretStore.SETTINGS_JSON))
    }

    /**
     * A read that FAILED is not an empty configuration — and the next save must be refused.
     *
     * This is the rule the class KDoc names and nothing was checking. Falling back to defaults is all a
     * reader can do, but saving them afterwards replaces a configuration we never managed to read with the
     * defaults we invented: one bad read at startup becomes permanent loss, with nothing having gone wrong
     * that the user could see. The unreadable-document path is the one that can be provoked from outside;
     * the unreachable-safe path sets the same flag in the same place.
     */
    fun `test a failed read refuses the next save`() {
        SecretStore.set(SecretStore.SETTINGS_JSON, "this is not a settings document")
        assertEquals(ClaudeSettings.State().model, SettingsStore.load().model) // fell back to defaults
        assertFalse(
            "a save after a failed read must be refused",
            SettingsStore.save(ClaudeSettings.State().apply { model = "defaults-must-not-win" }),
        )
        assertEquals("this is not a settings document", SecretStore.get(SecretStore.SETTINGS_JSON))
    }

    /** …and the veto lifts as soon as a read succeeds, or the settings would be read-only until a restart. */
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

    /**
     * A repository cannot turn off the permission prompts — for this project or for any project after it.
     *
     * `.idea/claude-code.xml` is committed with the code. While the settings were per project, a mode written
     * there governed that project and stopped at its edge; global settings turned the same file into a
     * machine-wide switch, and the migration that adopts it fires exactly when nothing is stored yet — a fresh
     * install opening its first project, which is the moment a hostile repo would be waiting for. Everything
     * else in the file still migrates; the mode does not.
     */
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
        assertEquals("from-the-old-file", loaded.model) // the rest of the file is still adopted
        assertEquals("Bash", loaded.allowedTools)
        // …and the component we were handed is untouched: mutating it in place would mark the legacy
        // PersistentStateComponent dirty and invite the platform to write claude-code.xml back out.
        assertEquals("bypassPermissions", legacy.permissionMode)
    }

    /** Same rule for a mode nothing recognises: it reaches the binary verbatim, so it is refused too. */
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

    /** A mode at least as strict as the default is a real setting and migrates like any other. */
    fun `test a legacy plan mode is adopted`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertTrue(
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { permissionMode = "plan" }),
        )
        assertEquals("plan", SettingsStore.load().permissionMode)
    }

    /**
     * A file whose ONLY content is a weakened mode carries nothing, and creates nothing.
     *
     * The refusal runs before the "does this carry anything?" check on purpose. The other way round, such a
     * file would write a document full of factory values and mark the migration done — the 5.5.0 bug where a
     * user's real configuration, sitting in a project opened later, never got its turn.
     */
    fun `test a legacy file carrying only a weakened mode migrates nothing at all`() {
        SecretStore.clear(SecretStore.SETTINGS_JSON)
        assertFalse(
            "a file whose only content is a refused mode carries nothing and must not migrate",
            SettingsStore.migrateFrom(ClaudeSettings.State().apply { permissionMode = "bypassPermissions" }),
        )
        assertNull("nothing was adopted, so no document may exist", SecretStore.get(SecretStore.SETTINGS_JSON))
    }
}
