package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.execution.services.ServiceViewActionUtils
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.swing.JTree

internal class ServiceActions(private val project: Project, private val node: ServiceNode) {

    class Entry(val id: String, val text: String, val enabled: Boolean, val action: AnAction, val event: AnActionEvent)

    fun list(): List<Entry> {
        val context = context()
        val actions = LinkedHashSet<AnAction>()
        expand(node.descriptor.toolbarActions, actions, 0)
        expand(node.descriptor.popupActions, actions, 0)
        return actions.map { action ->
            val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.SERVICES_POPUP, ActionUiKind.POPUP, null)
            ActionUtil.updateAction(action, event)
            val presentation = event.presentation
            val id = ActionManager.getInstance().getId(action).orEmpty()
            Entry(id, presentation.text.orEmpty(), presentation.isEnabledAndVisible, action, event)
        }
    }

    fun perform(name: String, scope: CoroutineScope): Entry {
        val entries = list()
        val entry = entries.firstOrNull { it.id.equals(name, ignoreCase = true) || it.text.equals(name, ignoreCase = true) }
            ?: throw ToolException("no action $name on ${node.path}; service_actions lists them: ${entries.joinToString { it.text }}")
        if (!entry.enabled) throw ToolException("the IDE has ${entry.text} disabled on ${node.path} right now")
        scope.launch(Dispatchers.EDT) { ActionUtil.performAction(entry.action, entry.event) }
        return entry
    }

    suspend fun reveal(): String {
        val manager = ServiceViewManager.getInstance(project)
        val selected = CompletableDeferred<Unit>()
        FocusKeeper.keeping(project) {
            manager.select(node.value, node.root.javaClass, true, false)
                .onSuccess { selected.complete(Unit) }
                .onError { selected.completeExceptionally(ToolException("the Services view could not reveal ${node.path}: ${it.message}")) }
        }
        withTimeoutOrNull(SELECT_TIMEOUT_MILLIS) { selected.await() }
            ?: throw ToolException("the Services view did not reveal ${node.path} in time; it may still be loading")
        return manager.getToolWindowId(node.root.javaClass) ?: ToolWindowId.SERVICES
    }

    private fun expand(group: ActionGroup?, into: MutableSet<AnAction>, depth: Int) {
        if (group !is DefaultActionGroup || depth > MAX_GROUP_DEPTH) return
        for (child in group.getChildren(ActionManager.getInstance())) {
            when (child) {
                is Separator -> Unit
                is ActionGroup -> expand(child, into, depth + 1)
                else -> into += child
            }
        }
    }

    var fromTree: Boolean = false
        private set

    fun context(): DataContext {
        val shown = viewContext()
        fromTree = shown != null
        if (shown != null) return shown
        val roots: Set<ServiceViewContributor<*>> = ServiceViewContributor.CONTRIBUTOR_EP_NAME.extensionList.toSet()
        val base = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.SELECTED_ITEM, node.value)
            .add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(node.value))
            .add(ServiceViewActionUtils.CONTRIBUTORS_KEY, roots)
            .build()
        val rootDescriptor = node.root.getViewDescriptor(project)
        return CustomizedDataContext.withSnapshot(base) { sink ->
            (rootDescriptor as? UiDataProvider)?.let(sink::uiDataSnapshot)
            (node.descriptor as? UiDataProvider)?.let(sink::uiDataSnapshot)
            node.descriptor.navigatable?.let { sink[CommonDataKeys.NAVIGATABLE] = it }
        }
    }

    private fun viewContext(): DataContext? {
        val id = ServiceViewManager.getInstance(project).getToolWindowId(node.root.javaClass) ?: ToolWindowId.SERVICES
        val contents = ToolWindowManager.getInstance(project).getToolWindow(id)?.contentManager?.contents ?: return null
        val selected = contents.asSequence()
            .flatMap { UIUtil.findComponentsOfType(it.component, JTree::class.java) }
            .filter { it.selectionCount > 0 }
            .map { DataManager.getInstance().getDataContext(it) }
            .filter { PlatformCoreDataKeys.SELECTED_ITEM.getData(it) != null }
            .toList()
        return selected.firstOrNull { sameItem(PlatformCoreDataKeys.SELECTED_ITEM.getData(it)) } ?: selected.singleOrNull()
    }

    private fun sameItem(item: Any?): Boolean = item == node.value || item.toString() == node.value.toString()

    private companion object {
        const val MAX_GROUP_DEPTH = 4
        const val SELECT_TIMEOUT_MILLIS = 10_000L
    }
}
