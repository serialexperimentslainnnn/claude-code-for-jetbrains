package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
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
        ClaudeSettings.getInstance(project).replaceState(ClaudeSettings.State())
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
            ClaudeSettingsConfigurable::class.java.getDeclaredMethod("rebuildModelCombo")
                .apply { isAccessible = true }.invoke(c)
            assertEquals("some-unlisted-model", modelComboOf(c).editor.item)
            assertFalse("a catalogue arriving is not a user edit", c.isModified())
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
