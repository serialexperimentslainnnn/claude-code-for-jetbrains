package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable
import dev.lain.claudejb.ui.SettingsModelSection
import javax.swing.JComboBox

/**
 * Headless: the Settings page builds, resets, detects modifications, and applies without starting any process.
 * Runs on the EDT (BasePlatformTestCase), so the Swing component work is safe.
 */
class ClaudeSettingsConfigurableHeadlessTest : BasePlatformTestCase() {

    /**
     * A store of this method's own before anything can write to it.
     *
     * `apply()` PERSISTS, and this class applies a fully-configured fixture state. The fixture's PasswordSafe
     * belongs to an Application the platform reuses for the whole run, so before this seam existed that
     * document (`model = opus-pinned-by-the-user`, `permissionMode = acceptEdits`) was simply still there
     * when the next test class started — measured, not theorised.
     */
    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
        // Reused light-fixture project service; restore defaults so isModified/reset assertions are stable.
        ClaudeSettings.getInstance(project).replaceState(ClaudeSettings.State())
    }

    private fun newConfigurable() = ClaudeSettingsConfigurable(project)

    /** The page is an assembler over one section per subject; the model combo belongs to [SettingsModelSection]. */
    private fun modelSectionOf(c: ClaudeSettingsConfigurable): SettingsModelSection =
        ClaudeSettingsConfigurable::class.java.getDeclaredField("modelSection")
            .apply { isAccessible = true }.get(c) as SettingsModelSection

    @Suppress("UNCHECKED_CAST")
    private fun modelComboOf(c: ClaudeSettingsConfigurable): JComboBox<String> {
        val field = SettingsModelSection::class.java.getDeclaredField("modelCombo")
        field.isAccessible = true
        return field.get(modelSectionOf(c)) as JComboBox<String>
    }

    /**
     * Opening the page asks the safe for the stored document off the EDT and answers back on it, so a method
     * that opened a page leaves work behind in two places. Draining both here — the queue, then the event
     * queue — is what keeps that work inside the method that started it: without it the answer lands in
     * whatever store the NEXT test class installs, replacing its settings mid-method.
     */
    private fun drainReload() {
        ClaudeSettings.awaitWrites()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            drainReload()
            SecretStore.storeOverride = null
            val manager = ChatSessionManager.getInstance(project)
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }

    fun `test createComponent returns a non-null component`() {
        val c = newConfigurable()
        try {
            assertNotNull(c.createComponent())
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test model combo reflects only binary models (editable, no hardcoded fallback)`() {
        val c = newConfigurable()
        try {
            c.createComponent()
            val combo = modelComboOf(c)
            val items = (0 until combo.itemCount).map { combo.getItemAt(it) }
            // The list is exactly what the binary reported (empty in headless, where init never lands) — no
            // hand-maintained fallback that duplicated/aged. The combo stays editable so a custom id can be typed.
            assertTrue("combo is editable", combo.isEditable)
            assertTrue("no hardcoded fallback entries", items.none { it == "sonnet" || it == "haiku" })
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test apply reflects the selected model into settings`() {
        val c = newConfigurable()
        try {
            c.createComponent()
            modelComboOf(c).selectedItem = "haiku"
            c.apply()
            assertEquals("haiku", ClaudeSettings.getInstance(project).state.model)
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test isModified is true after a change and false after reset`() {
        val c = newConfigurable()
        try {
            c.createComponent()
            // Fresh component matches persisted state.
            assertFalse(c.isModified())
            modelComboOf(c).selectedItem = "sonnet"
            assertTrue(c.isModified())
            c.reset()
            assertFalse(c.isModified())
        } finally {
            c.disposeUIResources()
        }
    }

    /**
     * REGRESSION (5.5.0): a persisted model the binary does NOT list must survive the dialog untouched.
     *
     * The combo is populated from the binary's catalogue, and a freshly populated `DefaultComboBoxModel`
     * selects its first entry. When the saved value was not among them nothing put it back, so merely opening
     * Settings showed someone else's model, `isModified` went true and Apply persisted it — that is how a
     * user's pinned Opus became `haiku`. The saved value is a legitimate selection (a custom id, a model this
     * build of the binary does not advertise) and the page must never quietly replace it.
     */
    fun `test a saved model absent from the binary catalogue is preserved`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.model = "some-unlisted-model"
        // The catalogue arrives in the `initialize` control REPLY, not as an event, so there is no
        // handleEventForTest path to it: the backing field is written directly, which is all this test needs.
        val session = ChatSessionManager.getInstance(project).activeOrCreate()
        ClaudeSession::class.java.getDeclaredField("models").apply { isAccessible = true }
            .set(session, listOf(ModelInfo("haiku"), ModelInfo("sonnet")))
        val c = newConfigurable()
        try {
            c.createComponent()
            val combo = modelComboOf(c)
            assertEquals("some-unlisted-model", combo.editor.item)
            assertFalse("opening Settings is not an edit", c.isModified())
            c.apply()
            assertEquals("some-unlisted-model", settings.state.model)
        } finally {
            c.disposeUIResources()
        }
    }

    /**
     * Same regression, on the path that actually happens: the `initialize` reply lands SECONDS after the page
     * was built, so the combo is repopulated with the dialog already open and the saved value on screen.
     */
    fun `test a late binary catalogue does not replace the saved model`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.model = "some-unlisted-model"
        val session = ChatSessionManager.getInstance(project).activeOrCreate()
        val c = newConfigurable()
        try {
            c.createComponent() // built while the catalogue is still empty
            ClaudeSession::class.java.getDeclaredField("models").apply { isAccessible = true }
                .set(session, listOf(ModelInfo("haiku"), ModelInfo("sonnet")))
            SettingsModelSection::class.java.getDeclaredMethod("rebuildModelCombo")
                .apply { isAccessible = true }.invoke(modelSectionOf(c))
            assertEquals("some-unlisted-model", modelComboOf(c).editor.item)
            assertFalse("a catalogue arriving is not a user edit", c.isModified())
        } finally {
            c.disposeUIResources()
        }
    }

    /**
     * FIELD OWNERSHIP: every persisted setting survives a trip through the page untouched.
     *
     * The page is seven sections now, and each answers for its own fields in `reset`, `apply` and
     * `changedFields`. The failure mode that split creates is invisible: a field claimed by NOBODY is never
     * loaded onto a widget, and a field that is applied without being reset is written back from a widget
     * still holding its factory default — Apply on an unrelated setting quietly resets it. Neither shows up
     * as an error, so this walks [ClaudeSettings.State] BY REFLECTION (not a hand-kept list, which is the
     * same mistake one level up) and requires the whole document to come back byte for byte.
     *
     * The four fields no section owns — `signedOut`, `enableFileCheckpointing`, `rewindFallback`,
     * `sensitiveExtraGlobs` — are deliberately not on the form, and this is also what pins that: they must
     * come back untouched, not clobbered.
     */
    fun `test every persisted field survives a trip through the page`() {
        val settings = ClaudeSettings.getInstance(project)
        val expected = configuredState()
        settings.replaceState(configuredState())
        val c = newConfigurable()
        try {
            c.createComponent() // resets the widgets from the state
            assertFalse("opening the page is not an edit", c.isModified())
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        val after = settings.state
        ClaudeSettings.State::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.name.startsWith("$") }
            .forEach { field ->
                field.isAccessible = true
                assertEquals(
                    "the Settings page lost or rewrote '${field.name}'",
                    field.get(expected),
                    field.get(after),
                )
            }
    }

    /**
     * …and the other half of the same invariant: which fields the form actually WRITES.
     *
     * The test above cannot see a section that resets a field but forgets to apply it — the value simply
     * stays where it was, and the user's edit is discarded in silence. So this loads the widgets from a
     * configured state, swaps the document underneath them for factory defaults, and applies: every field the
     * form owns must be written back from its widget, and every field it does not own must still read as the
     * default nobody touched. That is the ownership table, executable — and [FORM_OWNED] plus
     * [NOT_ON_THE_FORM] are required to cover [ClaudeSettings.State] exactly, so a new setting cannot be
     * added without someone deciding which side it is on.
     */
    fun `test the form writes exactly the fields it owns`() {
        val settings = ClaudeSettings.getInstance(project)
        val configured = configuredState()
        settings.replaceState(configuredState())
        val c = newConfigurable()
        try {
            c.createComponent() // widgets := configured
            settings.replaceState(ClaudeSettings.State()) // the document reverts under the open page
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        val after = settings.state
        val defaults = ClaudeSettings.State()
        val fields = ClaudeSettings.State::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.name.startsWith("$") }
        assertEquals(
            "every setting must be classified as on the form or deliberately off it",
            emptySet<String>(),
            fields.map { it.name }.toSet() - FORM_OWNED - NOT_ON_THE_FORM - UNWRITTEN_UNLESS_EDITED,
        )
        fields.forEach { field ->
            field.isAccessible = true
            when (field.name) {
                in FORM_OWNED -> assertEquals(
                    "the form owns '${field.name}' but did not write it — an edit there is discarded",
                    field.get(configured),
                    field.get(after),
                )

                in NOT_ON_THE_FORM -> assertEquals(
                    "no section owns '${field.name}', so applying the page must not touch it",
                    field.get(defaults),
                    field.get(after),
                )
            }
        }
        // `model` is the deliberate exception: the combo is repopulated from the binary's catalogue while the
        // page is open, so it is written ONLY when the user actually edited it. This is the pinned-Opus
        // regression — an OK on an unrelated setting must not persist whatever the widget happens to hold.
        assertEquals(defaults.model, after.model)
    }

    /** A state with every field on a non-default, legal value — legal meaning each combo really offers it. */
    private fun configuredState() = ClaudeSettings.State().apply {
        model = "opus-pinned-by-the-user"
        effort = "low"
        permissionMode = "acceptEdits"
        thinkingTokens = 0
        includePartialMessages = false
        settingSources = "user,local"
        allowedTools = "Read,Write"
        disallowedTools = "Bash"
        ideMcpEnabled = true
        ideMcpTransport = "stdio"
        ideMcpPort = 4711
        customMcpServers = """{"demo":{"type":"sse","url":"http://127.0.0.1:1/sse"}}"""
        claudePath = "/opt/claude/bin/claude"
        nodePath = "/opt/node/bin/node"
        provider = "anthropic" // a third-party provider would fail validate() without its own key
        envVars = "FOO=bar"
        sourceScript = "/opt/env.sh"
        alwaysAllowTools = "Read"
        restoreOpenChatsOnStartup = false
        reduceMotion = true
        // Any allowed value other than the default, so "the form wrote it back" is distinguishable from
        // "the form left the default alone".
        workloadWindowMinutes = 60
        // Two rules switched off, in the CANONICAL spelling (declaration order): the section rebuilds this field
        // from its checkboxes and compares the two strings to decide whether the page was edited, so a
        // non-canonical fixture would make "opening the page is not an edit" fail for a reason that is not a bug.
        disabledSecurityRules = "CREDENTIALS,TEMP_DIR"
        securityExtraBlockedDomains = "paste.example.com"
        maxTurns = 7
        maxBudgetUsd = 12.5
        fallbackModel = "sonnet"
        addDirs = "/tmp/a\n/tmp/b"
        betas = "beta-one"
        strictMcpConfig = true
        // Not on the form at all — they must come back untouched.
        signedOut = true
        enableFileCheckpointing = false
        rewindFallback = "never"
        sensitiveExtraGlobs = "**/secret.env"
    }

    private companion object {
        /** Field → the section that answers for it, as one flat set (the sections are named in the comments). */
        val FORM_OWNED = setOf(
            // SettingsModelSection
            "effort", "permissionMode", "thinkingTokens", "includePartialMessages",
            "restoreOpenChatsOnStartup", "reduceMotion", "workloadWindowMinutes",
            // SettingsSecuritySection — one field for every rule the user switched off, plus its own egress list.
            // The seven `securityBlock*` booleans that used to be here are NOT on the form any more: they are
            // migration inputs read once by LegacySecurityToggles, and the section must leave them alone.
            "disabledSecurityRules", "securityExtraBlockedDomains",
            // SettingsProviderSection (the key itself is not a state field — it is in the password safe)
            "provider",
            // SettingsExecutableSection
            "claudePath", "nodePath", "sourceScript", "envVars",
            // SettingsToolsSection
            "settingSources", "allowedTools", "disallowedTools", "alwaysAllowTools",
            // SettingsMcpSection
            "ideMcpEnabled", "ideMcpTransport", "ideMcpPort", "customMcpServers", "strictMcpConfig",
            // SettingsAdvancedSection
            "maxTurns", "maxBudgetUsd", "fallbackModel", "addDirs", "betas",
        )

        /**
         * Deliberately not on the Settings page: `signedOut` is written by the sign-in card, `rewindFallback`
         * is a remembered dialog answer, `enableFileCheckpointing` follows the rewind feature rather than a
         * checkbox, and `sensitiveExtraGlobs` has never had a field. Listed so they are a decision, not an
         * omission.
         *
         * The seven `securityBlock*` booleans are here for a different reason and only for one release: they are
         * **superseded** by `disabledSecurityRules` and are read exactly once, by `LegacySecurityToggles.adopt`,
         * so the form must neither draw them nor write them. Their presence in this bucket is what pins that —
         * a section that started writing one again would silently overwrite a migrated configuration.
         */
        val NOT_ON_THE_FORM = setOf(
            "signedOut", "enableFileCheckpointing", "rewindFallback", "sensitiveExtraGlobs",
            "securityBlockCredentials", "securityBlockDangerousCommands", "securityBlockTempDirs",
            "securityBlockForeignOtherUserHome", "securityBlockForeignNetworkMounts",
            "securityBlockForeignWslMounts", "securityBlockOutsideProject",
        )

        /** On the form, but written only on a real edit — see the assertion at the end of the test. */
        val UNWRITTEN_UNLESS_EDITED = setOf("model")
    }

    /**
     * REGRESSION: opening the page draws what is STORED, not a copy loaded when the service was created.
     *
     * The in-memory settings are loaded once per service and nothing invalidates them, so a change made in a
     * second IDE is invisible here indefinitely. That matters most on this page precisely because it is the
     * one surface that shows every field and then writes them ALL back: drawn from a stale copy, pressing OK
     * on an unrelated setting replaced the other IDE's whole configuration with this one's. So the page asks
     * the safe when it opens, and OK is measured against the answer.
     */
    fun `test opening the page adopts what another IDE stored`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.replaceState(ClaudeSettings.State()) // this IDE's copy, loaded before the other one wrote
        val elsewhere = ClaudeSettings.State().apply { permissionMode = "acceptEdits" }
        assertTrue("the fixture store must accept the write", SettingsStore.save(elsewhere))

        val c = newConfigurable()
        try {
            c.createComponent()
            drainReload()
            assertEquals("the page never re-read the safe", "acceptEdits", settings.state.permissionMode)
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        assertEquals(
            "OK on an untouched page replaced the other IDE's configuration",
            "acceptEdits",
            SettingsStore.load().permissionMode,
        )
    }

    /**
     * …and the other half: while the page is open, nothing from outside is applied over the user, and OK wins.
     *
     * The refresh is asynchronous — the read belongs off the EDT — so its answer can arrive after the user has
     * already typed. It is dropped in that case: a settings field that changes under someone's fingers between
     * their keystroke and their click is worse than a stale one, because they will click OK on a value they
     * never chose. What they typed is what gets stored.
     */
    fun `test an outside change is not applied under a typed edit and OK wins`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.replaceState(ClaudeSettings.State())
        val elsewhere = ClaudeSettings.State().apply {
            model = "from-the-other-ide"
            sensitiveExtraGlobs = "**/other.env" // no section owns this one: adoption is visible through it
        }
        assertTrue("the fixture store must accept the write", SettingsStore.save(elsewhere))

        val c = newConfigurable()
        try {
            c.createComponent() // the refresh is in flight; the user is quicker than the keyring
            modelComboOf(c).selectedItem = "typed-by-the-user"
            assertTrue(c.isModified())
            drainReload() // the answer arrives with an edit already on screen
            assertEquals(
                "an outside change was applied over what the user had typed",
                "typed-by-the-user",
                modelComboOf(c).editor.item,
            )
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        val stored = SettingsStore.load()
        assertEquals("OK did not win", "typed-by-the-user", stored.model)
        assertEquals(
            "the refresh was skipped instead of merely not drawn, so the other IDE's field was clobbered",
            "**/other.env",
            stored.sensitiveExtraGlobs,
        )
    }

    fun `test disposeUIResources does not throw`() {
        val c = newConfigurable()
        c.createComponent()
        c.disposeUIResources()
        // Idempotent second call.
        c.disposeUIResources()
    }
}
