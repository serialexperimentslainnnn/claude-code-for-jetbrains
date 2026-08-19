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

class ClaudeSettingsConfigurableHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load()
        ClaudeSettings.getInstance(project).replaceState(ClaudeSettings.State())
    }

    private fun newConfigurable() = ClaudeSettingsConfigurable(project)

    private fun modelSectionOf(c: ClaudeSettingsConfigurable): SettingsModelSection =
        ClaudeSettingsConfigurable::class.java.getDeclaredField("modelSection")
            .apply { isAccessible = true }.get(c) as SettingsModelSection

    @Suppress("UNCHECKED_CAST")
    private fun modelComboOf(c: ClaudeSettingsConfigurable): JComboBox<String> {
        val field = SettingsModelSection::class.java.getDeclaredField("modelCombo")
        field.isAccessible = true
        return field.get(modelSectionOf(c)) as JComboBox<String>
    }

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
            assertFalse(c.isModified())
            modelComboOf(c).selectedItem = "sonnet"
            assertTrue(c.isModified())
            c.reset()
            assertFalse(c.isModified())
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test a saved model absent from the binary catalogue is preserved`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.model = "some-unlisted-model"
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

    fun `test a late binary catalogue does not replace the saved model`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.state.model = "some-unlisted-model"
        val session = ChatSessionManager.getInstance(project).activeOrCreate()
        val c = newConfigurable()
        try {
            c.createComponent()
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

    fun `test every persisted field survives a trip through the page`() {
        val settings = ClaudeSettings.getInstance(project)
        val expected = configuredState()
        settings.replaceState(configuredState())
        val c = newConfigurable()
        try {
            c.createComponent()
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

    fun `test the form writes exactly the fields it owns`() {
        val settings = ClaudeSettings.getInstance(project)
        val configured = configuredState()
        settings.replaceState(configuredState())
        val c = newConfigurable()
        try {
            c.createComponent()
            settings.replaceState(ClaudeSettings.State())
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
        assertEquals(defaults.model, after.model)
    }

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
        provider = "anthropic"
        envVars = "FOO=bar"
        sourceScript = "/opt/env.sh"
        alwaysAllowTools = "Read"
        restoreOpenChatsOnStartup = false
        reduceMotion = true
        workloadWindowMinutes = 60
        disabledSecurityRules = "CREDENTIALS,TEMP_DIR"
        securityExtraBlockedDomains = "paste.example.com"
        securityCommandWhitelist = "terraform destroy"
        maxTurns = 7
        maxBudgetUsd = 12.5
        fallbackModel = "sonnet"
        addDirs = "/tmp/a\n/tmp/b"
        betas = "beta-one"
        strictMcpConfig = true
        signedOut = true
        enableFileCheckpointing = false
        rewindFallback = "never"
        sensitiveExtraGlobs = "**/secret.env"
    }

    private companion object {
        val FORM_OWNED = setOf(
            "effort", "permissionMode", "thinkingTokens", "includePartialMessages",
            "restoreOpenChatsOnStartup", "reduceMotion", "workloadWindowMinutes",
            "disabledSecurityRules", "securityExtraBlockedDomains", "securityCommandWhitelist",
            "provider",
            "claudePath", "nodePath", "sourceScript", "envVars",
            "settingSources", "allowedTools", "disallowedTools", "alwaysAllowTools",
            "ideMcpEnabled", "ideMcpTransport", "ideMcpPort", "customMcpServers", "strictMcpConfig",
            "maxTurns", "maxBudgetUsd", "fallbackModel", "addDirs", "betas",
        )

        val NOT_ON_THE_FORM = setOf(
            "signedOut", "enableFileCheckpointing", "rewindFallback", "sensitiveExtraGlobs",
            "securityBlockCredentials", "securityBlockDangerousCommands", "securityBlockTempDirs",
            "securityBlockForeignOtherUserHome", "securityBlockForeignNetworkMounts",
            "securityBlockForeignWslMounts", "securityBlockOutsideProject",
            "securityRuleSuspensions", "securityCommandApprovals",
        )

        val UNWRITTEN_UNLESS_EDITED = setOf("model")
    }

    fun `test opening the page adopts what another IDE stored`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.replaceState(ClaudeSettings.State())
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

    fun `test an outside change is not applied under a typed edit and OK wins`() {
        val settings = ClaudeSettings.getInstance(project)
        settings.replaceState(ClaudeSettings.State())
        val elsewhere = ClaudeSettings.State().apply {
            model = "from-the-other-ide"
            sensitiveExtraGlobs = "**/other.env"
        }
        assertTrue("the fixture store must accept the write", SettingsStore.save(elsewhere))

        val c = newConfigurable()
        try {
            c.createComponent()
            modelComboOf(c).selectedItem = "typed-by-the-user"
            assertTrue(c.isModified())
            drainReload()
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
        c.disposeUIResources()
    }
}
