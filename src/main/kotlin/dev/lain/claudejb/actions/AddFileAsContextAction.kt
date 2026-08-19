package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.lain.claudejb.context.EditorContextProvider

class AddFileAsContextAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val attachment = EditorContextProvider.currentFileAsAttachment(project) ?: return
        AttachmentActions.pin(project, attachment)
    }

    override fun update(e: AnActionEvent) {
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        e.presentation.isEnabledAndVisible = hasFile && e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
