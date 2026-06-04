package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.settings.Provider

/**
 * Builds the gear menu that exposes Claude Code's runtime options graphically: model, effort,
 * permission mode and thinking. Rendered as a nested action-group popup from the tool window title bar.
 */
object OptionMenus {

    fun buildOptionsGroup(session: ClaudeSession): DefaultActionGroup {
        val root = DefaultActionGroup("Claude Options", true)
        root.add(providerGroup(session))
        root.add(modelGroup(session))
        root.add(effortGroup(session))
        root.add(permissionModeGroup(session))
        root.add(thinkingGroup(session))
        return root
    }

    /**
     * API provider picker. Selecting a third-party provider with no stored key prompts to configure it (in
     * Settings) and does NOT switch/restart — the key gating lives in [ClaudeSession.changeProvider].
     */
    fun providerGroup(session: ClaudeSession) = DefaultActionGroup("Provider (applies on restart)", true).apply {
        Provider.entries.forEach { p ->
            add(ProviderChoice(p.label, ChatTheme.providerIcon(p), { session.provider == p }) { session.changeProvider(p) })
        }
    }

    fun modelGroup(session: ClaudeSession) = DefaultActionGroup("Model", true).apply {
        session.modelOptions().forEach { m ->
            add(Choice(m.displayName.ifBlank { m.value }) { session.model == m.value }
                .onChosen { session.changeModel(m.value) })
        }
    }

    fun effortGroup(session: ClaudeSession) = DefaultActionGroup("Effort (applies on restart)", true).apply {
        add(Choice("Default") { session.effort == null }.onChosen { session.changeEffort(null) })
        ClaudeSession.EFFORT_LEVELS.forEach { level ->
            add(Choice(level) { session.effort == level }.onChosen { session.changeEffort(level) })
        }
    }

    fun permissionModeGroup(session: ClaudeSession) = DefaultActionGroup("Permission mode", true).apply {
        ClaudeSession.PERMISSION_MODES.forEach { mode ->
            add(Choice(dev.lain.claudejb.session.PermissionMode.labelFor(mode)) { session.permissionMode == mode }
                .onChosen { session.changePermissionMode(mode) })
        }
    }

    fun thinkingGroup(session: ClaudeSession) = DefaultActionGroup("Thinking", true).apply {
        // Adaptive thinking is on/off: the model decides depth, so there's no token budget to pick.
        add(Choice("Off") { session.thinkingTokens == null }.onChosen { session.changeThinkingTokens(null) })
        add(Choice("On (adaptive)") { session.thinkingTokens != null }.onChosen { session.changeThinkingTokens(ClaudeSession.THINKING_ON) })
    }

    /**
     * Runtime diagnostics submenu: the interactive MCP runtime panel (reconnect/toggle per server), the
     * binary version and the effective merged settings. Each item opens the matching [InfoDialogs] dialog,
     * which queries the live session asynchronously. Needs the [project] for the dialog parent, so it is not
     * part of [buildOptionsGroup] (which only takes a session); the gear menu wires it in directly.
     */
    fun diagnosticsGroup(project: Project, session: ClaudeSession) = DefaultActionGroup("Diagnostics", true).apply {
        add(Action("MCP Servers…") { InfoDialogs.showMcpStatus(project, session) })
        add(Action("Binary Version…") { InfoDialogs.showBinaryVersion(project, session) })
        add(Action("Effective Settings…") { InfoDialogs.showEffectiveSettings(project, session) })
    }

    /** A plain menu item that runs [run] on click and closes the popup. */
    private class Action(text: String, private val run: () -> Unit) : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) = run()
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * A radio-style choice: shows a check when selected and runs its effect on click. It is a plain [AnAction]
     * (not a ToggleAction) so the popup closes on selection — a ToggleAction keeps the popup open to allow
     * multiple toggles, which is wrong for a single-pick option menu.
     */
    private class Choice(text: String, private val selected: () -> Boolean) : AnAction(text) {
        private var chosen: () -> Unit = {}
        fun onChosen(block: () -> Unit): Choice = apply { chosen = block }
        override fun actionPerformed(e: AnActionEvent) = chosen()
        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (selected()) AllIcons.Actions.Checked else null
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * A provider menu item that always shows the provider's **brand logo** as its icon (so each option reads
     * as its provider), and marks the active one with a trailing check in the label (since the icon slot is
     * taken by the logo, not the usual checkmark).
     */
    private class ProviderChoice(
        private val label: String,
        private val brand: javax.swing.Icon,
        private val selected: () -> Boolean,
        private val chosen: () -> Unit,
    ) : AnAction(label, null, brand) {
        override fun actionPerformed(e: AnActionEvent) = chosen()
        override fun update(e: AnActionEvent) {
            e.presentation.icon = brand
            e.presentation.text = if (selected()) "$label  ✓" else label
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
