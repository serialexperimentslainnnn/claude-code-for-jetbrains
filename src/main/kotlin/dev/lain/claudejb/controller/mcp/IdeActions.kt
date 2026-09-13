package dev.lain.claudejb.controller.mcp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class IdeActions(private val project: Project, private val scope: CoroutineScope) {

    private val targets = TargetContext(project)

    suspend fun dispatch(actionId: String, context: DataContext? = null) {
        val resolved = context ?: withContext(Dispatchers.EDT) { targets.project() }
        withContext(Dispatchers.EDT) {
            val (action, event) = enabled(actionId, resolved)
            scope.launch(Dispatchers.EDT) { FocusKeeper.keeping(project) { ActionUtil.performAction(action, event) } }
        }
    }

    suspend fun dispatch(actionId: String, target: TargetContext.Target) = dispatch(actionId, targets.of(target))

    suspend fun toggle(actionId: String, on: Boolean?): Boolean = withContext(Dispatchers.EDT) {
        val (action, event) = enabled(actionId, targets.project())
        val selected = Toggleable.isSelected(event.presentation)
        if (on == null || on != selected) FocusKeeper.keeping(project) { ActionUtil.performAction(action, event) }
        on ?: !selected
    }

    private fun enabled(actionId: String, context: DataContext): Pair<AnAction, AnActionEvent> {
        val action = ActionManager.getInstance().getAction(actionId)
            ?: throw ToolException("this IDE has no action $actionId; the plugin that provides it is not installed")
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
        ActionUtil.updateAction(action, event)
        if (!event.presentation.isEnabled || !event.presentation.isVisible) {
            throw ToolException("the IDE refused $actionId in this context: it is not enabled here")
        }
        return action to event
    }
}
