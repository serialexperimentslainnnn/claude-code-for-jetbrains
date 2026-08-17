package dev.lain.claudejb.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page (Settings ▸ Claude Code) exposing the launch defaults graphically. Applies changes to
 * the live session and persists them via [ClaudeSettings].
 *
 * A **thin assembler**: every widget belongs to a [SettingsSection], and the page only decides their order,
 * wraps them for a wide monitor, and sequences validate → apply → save. New behaviour goes in a section.
 */
class ClaudeSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeSettings.getInstance(project)
    private val session: ClaudeSession get() = ChatSessionManager.getInstance(project).activeOrCreate()

    private val modelSection = SettingsModelSection { session }
    private val securitySection = SettingsSecuritySection()
    private val providerSection = SettingsProviderSection(settings)
    private val executableSection = SettingsExecutableSection()
    private val toolsSection = SettingsToolsSection(settings)
    private val mcpSection = SettingsMcpSection()
    private val advancedSection = SettingsAdvancedSection()

    /** The page, in page order. Building, resetting, comparing and applying all walk this one list. */
    private val sections: List<SettingsSection> = listOf(
        modelSection,
        securitySection,
        providerSection,
        executableSection,
        toolsSection,
        mcpSection,
        advancedSection,
    )

    override fun getDisplayName(): String = "Claude Code"

    /**
     * The settings document the form currently shows.
     *
     * Not the same object as `settings.state` at every moment, and that is what it is for: a reload can replace
     * the in-memory copy while this page is open, and "has the user changed anything" must keep being asked
     * against what the user was actually shown.
     */
    private var shown: ClaudeSettings.State? = null

    override fun createComponent(): JComponent {
        var form = FormBuilder.createFormBuilder()
        sections.forEach { form = it.addTo(form) }
        val built = form.addComponentFillVertically(JPanel(), 0).panel
        reset()
        // The in-memory settings are loaded once per service and nothing invalidates them, so another IDE's
        // change is invisible until something asks. Opening this page is that moment: it is the one surface
        // that shows every field and then writes them all back, so drawing it from a stale copy is how one
        // IDE's settings silently replace the other's. The answer arrives asynchronously (the read belongs
        // off the EDT) and is dropped if the user has already typed — nothing changes under their fingers.
        settings.reload { if (!isModified()) reset() }
        // Pin the form to its preferred width on the LEFT instead of letting it stretch edge-to-edge: on a wide
        // (2K+) monitor a full-width form spread the text fields and the 4-column tool grids across the whole
        // screen. BorderLayout.WEST gives `built` its preferred width (now bounded, since the HTML notes wrap at
        // SETTINGS_FORM_WIDTH) and leaves the rest of the page blank — a tidy, fixed-width settings column.
        val holder = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 0, JBUIScale.scale(12))
            add(built, java.awt.BorderLayout.WEST)
        }
        // Wrap in a scroll pane so the (long) form stays usable on small screens and doesn't force the Settings
        // dialog to balloon; the inner form keeps its preferred width and the viewport tracks it (responsive).
        return JBScrollPane(holder).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            verticalScrollBar.unitIncrement = JBUIScale.scale(16)
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    /** False before the page is built: with no widgets, nothing has been changed. */
    override fun isModified(): Boolean =
        shown?.let { s -> sections.any { section -> section.changedFields(s).any { it } } } ?: false

    override fun apply() {
        // The WHOLE page is validated first: a section that refuses its input must not leave the sections
        // before it already written into the settings document.
        sections.forEach { it.validate() }
        val s = settings.state
        sections.forEach { it.apply(s) }
        // The form edits the whole state in bulk, so it saves once at the end rather than through
        // `update {}` per field. Since 5.5.0 nothing persists for us: without this, everything above is
        // in memory only and gone at the next restart.
        //
        // Whole, and deliberately: this is the one write that is allowed to overwrite what another IDE stored
        // while the page was open. The user is looking at every field and pressing OK on all of them.
        settings.save()
        settings.applyTo(session)
        shown = s
    }

    override fun reset() {
        val s = settings.state
        sections.forEach { it.reset(s) }
        shown = s
    }

    override fun disposeUIResources() = sections.forEach { it.dispose() }
}
