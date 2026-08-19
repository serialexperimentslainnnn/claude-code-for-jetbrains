package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.lain.claudejb.context.EditorContextProvider

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
