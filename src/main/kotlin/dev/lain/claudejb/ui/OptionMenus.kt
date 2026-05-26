package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import dev.lain.claudejb.session.ClaudeSession

/**
 * Builds the gear menu that exposes Claude Code's runtime options graphically: model, effort,
 * permission mode and thinking. Rendered as a nested action-group popup from the tool window title bar.
 */
object OptionMenus {

    fun buildOptionsGroup(session: ClaudeSession): DefaultActionGroup {
        val root = DefaultActionGroup("Claude Options", true)
        root.add(modelGroup(session))
        root.add(effortGroup(session))
        root.add(permissionModeGroup(session))
        root.add(thinkingGroup(session))
        return root
    }

    fun modelGroup(session: ClaudeSession) = DefaultActionGroup("Model", true).apply {
        // The binary's initialize already returns a `value:"default"` entry ("Default (recommended)"), so we
        // must NOT add our own "Default" too — that produced the duplicate ("Default" + "Default (recommended)").
        // We map that entry to "no --model flag" (model == null) and treat both states as the same selection.
        if (session.models.none { it.value == "default" }) {
            add(Choice("Default") { session.model == null }.onChosen { session.changeModel(null) })
        }
        session.models.forEach { m ->
            val isDefault = m.value == "default"
            add(
                Choice(m.displayName.ifBlank { m.value }) {
                    if (isDefault) session.model == null || session.model == "default" else session.model == m.value
                }.onChosen { if (isDefault) session.changeModel(null) else session.changeModel(m.value) }
            )
        }
        // opusplan is a CLI alias (auto-scales the model to the task: Opus for hard work, Sonnet for the rest),
        // not returned by initialize, so we add it by hand.
        add(Choice("Opusplan (auto: Opus/Sonnet by task)") { session.model == "opusplan" }.onChosen { session.changeModel("opusplan") })
    }

    fun effortGroup(session: ClaudeSession) = DefaultActionGroup("Effort (applies on restart)", true).apply {
        add(Choice("Default") { session.effort == null }.onChosen { session.changeEffort(null) })
        ClaudeSession.EFFORT_LEVELS.forEach { level ->
            add(Choice(level) { session.effort == level }.onChosen { session.changeEffort(level) })
        }
    }

    fun permissionModeGroup(session: ClaudeSession) = DefaultActionGroup("Permission mode", true).apply {
        ClaudeSession.PERMISSION_MODES.forEach { mode ->
            add(Choice(mode) { session.permissionMode == mode }.onChosen { session.changePermissionMode(mode) })
        }
    }

    fun thinkingGroup(session: ClaudeSession) = DefaultActionGroup("Thinking", true).apply {
        add(Choice("Off") { session.thinkingTokens == null }.onChosen { session.changeThinkingTokens(null) })
        add(Choice("On (8k)") { session.thinkingTokens == 8_000 }.onChosen { session.changeThinkingTokens(8_000) })
        add(Choice("On (24k)") { session.thinkingTokens == 24_000 }.onChosen { session.changeThinkingTokens(24_000) })
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
}
