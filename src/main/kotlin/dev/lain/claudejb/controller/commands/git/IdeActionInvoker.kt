package dev.lain.claudejb.controller.commands.git

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.git.JcefGitData
import dev.lain.claudejb.view.window.ClaudeToolWindowFactory

internal object IdeActionInvoker {

    private val LOG = logger<IdeActionInvoker>()

    fun invoke(project: Project, actionId: String, gitActionId: String): JcefGitData.ActionState {
        val target = ActionManager.getInstance().getAction(actionId) ?: run {
            LOG.warn("This IDE has no action '$actionId'; the Git view's '$gitActionId' button does nothing")
            return JcefGitData.ActionState.FAILED
        }
        val component = ClaudeToolWindowFactory.contextComponent(project)
        val context = if (component != null) {
            DataManager.getInstance().getDataContext(component)
        } else {
            SimpleDataContext.getProjectContext(project)
        }
        val event = AnActionEvent.createEvent(
            target,
            context,
            null,
            ActionPlaces.TOOLWINDOW_CONTENT,
            ActionUiKind.TOOLBAR,
            null,
        )
        ActionUtil.updateAction(target, event)
        if (!event.presentation.isEnabled || !event.presentation.isVisible) {
            LOG.warn("The IDE refused '$actionId' in this context (enabled=${event.presentation.isEnabled})")
            return JcefGitData.ActionState.FAILED
        }
        val performed = ActionUtil.performAction(target, event).isPerformed
        return if (performed) JcefGitData.ActionState.COMPLETED else JcefGitData.ActionState.FAILED
    }
}
