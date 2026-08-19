package dev.lain.claudejb.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.ClaudeToolWindowFactory

object AttachmentActions {

    fun pin(project: Project, attachment: Attachment) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ClaudeToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow == null) {
            send(project, attachment)
            return
        }
        toolWindow.activate {
            val panel = ClaudeToolWindowFactory.activePanel(project)
            if (panel != null) panel.addAttachment(attachment) else send(project, attachment)
        }
    }

    private fun send(project: Project, attachment: Attachment) =
        ChatSessionManager.getInstance(project).activeOrCreate().send(attachment.toPromptText())
}
