package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.lain.claudejb.context.EditorContextProvider

/**
 * Editor-popup action: pin the active file as an [dev.lain.claudejb.context.Attachment.FileRef] chip on the active
 * chat composer (via [AttachmentActions.pin]), so it travels with the user's next prompt. Enabled only when there
 * is a focused editor under a project.
 */
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

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
