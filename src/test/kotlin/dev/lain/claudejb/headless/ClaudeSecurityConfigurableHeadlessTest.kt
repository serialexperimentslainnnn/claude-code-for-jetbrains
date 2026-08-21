package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsStore
import dev.lain.claudejb.ui.ClaudeSecurityConfigurable
import dev.lain.claudejb.ui.SettingsSecuritySection
import javax.swing.JComboBox

class ClaudeSecurityConfigurableHeadlessTest : BasePlatformTestCase() {

    private val scope get() = ClaudeSettings.getInstance(project).scope

    override fun setUp() {
        super.setUp()
        SecretStore.storeOverride = mutableMapOf()
        SettingsStore.load(scope)
        ClaudeSettings.getInstance(project).replaceState(ClaudeSettings.State())
    }

    override fun tearDown() {
        try {
            ClaudeSettings.awaitWrites()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            SecretStore.storeOverride = null
        } finally {
            super.tearDown()
        }
    }

    private fun newConfigurable() = ClaudeSecurityConfigurable(project)

    @Suppress("UNCHECKED_CAST")
    private fun modesOf(c: ClaudeSecurityConfigurable): Map<SecurityRule, JComboBox<GuardMode>> {
        val section = ClaudeSecurityConfigurable::class.java.getDeclaredField("rulesSection")
            .apply { isAccessible = true }.get(c) as SettingsSecuritySection
        return SettingsSecuritySection::class.java.getDeclaredField("modes")
            .apply { isAccessible = true }.get(section) as Map<SecurityRule, JComboBox<GuardMode>>
    }

    fun `test createComponent returns a non-null component`() {
        val c = newConfigurable()
        try {
            assertNotNull(c.createComponent())
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test every rule reaches the page, in one collapsible group per category`() {
        val c = newConfigurable()
        try {
            c.createComponent()
            assertEquals(
                "a rule with no control is a rule nobody can relax when it fires on real work",
                SecurityRule.entries.toSet(),
                modesOf(c).keys,
            )
        } finally {
            c.disposeUIResources()
        }
    }

    fun `test opening the page is not an edit, and everything it holds survives OK`() {
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
        PAGE_OWNED.forEach { name ->
            val field = ClaudeSettings.State::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertEquals("the Security page lost or rewrote '$name'", field.get(expected), field.get(after))
        }
    }

    fun `test the page writes exactly the fields it owns`() {
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
            "every setting must be classified as on this page or deliberately off it",
            emptySet<String>(),
            fields.map { it.name }.toSet() - PAGE_OWNED - NOT_ON_THE_PAGE,
        )
        fields.forEach { field ->
            field.isAccessible = true
            when (field.name) {
                in PAGE_OWNED -> assertEquals(
                    "the page owns '${field.name}' but did not write it — an edit there is discarded",
                    field.get(configured),
                    field.get(after),
                )

                in NOT_ON_THE_PAGE -> assertEquals(
                    "no section owns '${field.name}', so applying this page must not touch it",
                    field.get(defaults),
                    field.get(after),
                )
            }
        }
    }

    fun `test moving one rule to Permissive is a change, and reset takes it back`() {
        val settings = ClaudeSettings.getInstance(project)
        val c = newConfigurable()
        try {
            c.createComponent()
            assertFalse(c.isModified())

            modesOf(c).getValue(SecurityRule.entries.first()).selectedItem = GuardMode.PERMISSIVE
            assertTrue("relaxing a rule has to register as an edit", c.isModified())

            c.reset()
            assertFalse("reset discards it", c.isModified())

            modesOf(c).getValue(SecurityRule.entries.first()).selectedItem = GuardMode.PERMISSIVE
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        assertEquals(SecurityRule.entries.first().name, settings.state.disabledSecurityRules)
    }

    fun `test a suspension the user is watching count down is not ended by pressing OK`() {
        val settings = ClaudeSettings.getInstance(project)
        val hour = 60L * 60 * 1000
        settings.replaceState(
            ClaudeSettings.State().apply { guardDisabledUntil = System.currentTimeMillis() + hour },
        )
        val c = newConfigurable()
        try {
            c.createComponent()
            c.apply()
        } finally {
            c.disposeUIResources()
        }
        assertTrue(
            "re-applying an untouched page must not restart or cancel a timed Allow All",
            settings.state.guardDisabledUntil > System.currentTimeMillis(),
        )
    }

    fun `test disposeUIResources does not throw`() {
        val c = newConfigurable()
        c.createComponent()
        c.disposeUIResources()
    }

    private fun configuredState() = ClaudeSettings.State().apply {
        guardMode = GuardMode.PERMISSIVE.wire
        guardDisabledUntil = 0
        guardLogRetentionDays = 90
        disabledSecurityRules = SecurityRule.canonicalCsv(SecurityRule.entries.take(2).map { it.name })
        securityExtraBlockedDomains = "paste.example.com"
        sensitiveExtraGlobs = "**/secret.env"
        securityCommandWhitelist = "terraform destroy"
        securityCategoryWhitelists = "${SecurityCategory.entries.first().name}=kubectl delete ns demo"
        securityRuleWhitelists = "${SecurityRule.entries.first().name}=cat ~/.aws/config"
    }

    private companion object {
        val PAGE_OWNED = setOf(
            "guardMode",
            "guardDisabledUntil",
            "guardLogRetentionDays",
            "disabledSecurityRules",
            "securityExtraBlockedDomains",
            "sensitiveExtraGlobs",
            "securityCommandWhitelist",
            "securityCategoryWhitelists",
            "securityRuleWhitelists",
        )

        val NOT_ON_THE_PAGE = setOf(
            "model", "effort", "permissionMode", "thinkingTokens", "includePartialMessages",
            "restoreOpenChatsOnStartup", "reduceMotion", "workloadWindowMinutes",
            "provider", "claudePath", "nodePath", "sourceScript", "envVars",
            "settingSources", "allowedTools", "disallowedTools", "alwaysAllowTools",
            "ideMcpEnabled", "ideMcpTransport", "ideMcpPort", "customMcpServers", "strictMcpConfig",
            "maxTurns", "maxBudgetUsd", "fallbackModel", "addDirs", "betas",
            "enableFileCheckpointing", "rewindFallback", "executionTrusted",
            "securityRuleSuspensions", "vulnConsent",
            "securityBlockCredentials", "securityBlockDangerousCommands", "securityBlockTempDirs",
            "securityBlockForeignOtherUserHome", "securityBlockForeignNetworkMounts",
            "securityBlockForeignWslMounts", "securityBlockOutsideProject",
        )
    }
}
