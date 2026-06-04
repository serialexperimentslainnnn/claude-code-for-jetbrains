package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable
import javax.swing.JComboBox

/**
 * Headless: the Settings page builds, resets, detects modifications, and applies without starting any process.
 * Runs on the EDT (BasePlatformTestCase), so the Swing component work is safe.
 */
class ClaudeSettingsConfigurableHeadlessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Reused light-fixture project service; restore defaults so isModified/reset assertions are stable.
        ClaudeSettings.getInstance(project).loadState(ClaudeSettings.State())
    }

    private fun newConfigurable() = ClaudeSettingsConfigurable(project)

    @Suppress("UNCHECKED_CAST")
    private fun modelComboOf(c: ClaudeSettingsConfigurable): JComboBox<String> {
        val field = ClaudeSettingsConfigurable::class.java.getDeclaredField("modelCombo")
        field.isAccessible = true
        return field.get(c) as JComboBox<String>
    }

    override fun tearDown() {
        try {
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

    fun `test disposeUIResources does not throw`() {
        val c = newConfigurable()
        c.createComponent()
        c.disposeUIResources()
        // Idempotent second call.
        c.disposeUIResources()
    }
}
