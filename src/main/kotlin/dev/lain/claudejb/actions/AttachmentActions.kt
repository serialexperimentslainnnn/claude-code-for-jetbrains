package dev.lain.claudejb.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.JcefChatPanel

/**
 * Shared routing for the "add … as @-context" editor actions: pins an [Attachment] onto the **active chat tab's**
 * composer so it shows as a removable chip and travels with the user's next prompt (instead of being sent
 * immediately). Activates the Claude Code tool window so the user sees the pinned chip.
 *
 * Falls back to sending the attachment's prompt-text directly only when the tool window has no chat panel yet
 * (e.g. the very first invocation before the window is built) — guaranteeing the context still reaches the agent.
 */
object AttachmentActions {

    fun pin(project: Project, attachment: Attachment) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Code")
        val panel = toolWindow?.contentManager?.selectedContent?.component as? JcefChatPanel
        if (panel != null) {
            panel.addAttachment(attachment)
            toolWindow.activate(null)
        } else {
            // No panel built yet: ensure the context reaches the agent rather than being dropped.
            ChatSessionManager.getInstance(project).activeOrCreate().send(attachment.toPromptText())
            toolWindow?.activate(null)
        }
    }
}
