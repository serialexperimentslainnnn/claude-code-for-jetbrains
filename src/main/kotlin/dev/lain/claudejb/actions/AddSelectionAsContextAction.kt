package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.lain.claudejb.context.EditorContextProvider

/**
 * Editor-popup action: capture the current selection as an [dev.lain.claudejb.context.Attachment.Selection] and pin
 * it as a chip on the active chat composer (via [AttachmentActions.pin]), so it travels with the user's next prompt.
 * Enabled only when there is a non-empty selection in the focused editor.
 */
class AddSelectionAsContextAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val attachment = EditorContextProvider.selectionAsAttachment(project) ?: return
        AttachmentActions.pin(project, attachment)
    }

    override fun update(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection && e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
