package dev.lain.claudejb.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.ClaudeToolWindowFactory

/**
 * Shared routing for the "add … as @-context" editor actions: pins an [Attachment] onto the **active chat tab's**
 * composer so it shows as a removable chip and travels with the user's next prompt (instead of being sent
 * immediately).
 *
 * The tool window is activated FIRST, and the pin happens in the callback: the chats are built by
 * `ClaudeToolWindowFactory.createToolWindowContent`, so before the window has been opened once there is no panel
 * to pin onto — and asking for one then is what made this action fall through to "just send it".
 *
 * Falls back to sending the attachment's prompt-text directly only when there is still no chat panel afterwards,
 * so the context reaches the agent rather than being dropped.
 */
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

    /** Last resort: the context becomes a turn of its own rather than being lost. */
    private fun send(project: Project, attachment: Attachment) =
        ChatSessionManager.getInstance(project).activeOrCreate().send(attachment.toPromptText())
}
