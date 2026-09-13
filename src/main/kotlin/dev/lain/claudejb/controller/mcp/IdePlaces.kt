package dev.lain.claudejb.controller.mcp

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.build.BuildContentManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.controller.git.GitLogNavigator
import dev.lain.claudejb.model.diff.DiffPresenter
import java.nio.file.Path

internal class IdePlaces(private val project: Project) {

    private class Verb(val key: String, val required: Boolean = true, val open: (String) -> Boolean)

    private val verbs: Map<String, Verb> = mapOf(
        "commit" to Verb("hash") { GitLogNavigator.showCommit(project, it, focus = true) },
        "log" to Verb("", required = false) { GitLogNavigator.showLog(project, focus = true) },
        "toolwindow" to Verb("id", open = ::activate),
        "terminal" to Verb("tab", required = false, open = ::terminal),
        "run" to Verb("name", open = ::run),
        "build" to Verb("", required = false) { build() },
        "problems" to Verb("tab", required = false, open = ::problems),
        "diff" to Verb("file", open = ::diff),
        "action" to Verb("id", open = ::action),
    )

    fun open(verb: String, params: Map<String, String>): Boolean {
        val known = verbs[verb] ?: return false
        val value = params[known.key] ?: if (known.required) return false else ""
        return known.open(value)
    }

    private fun build(): Boolean {
        BuildContentManager.getInstance(project).getOrCreateToolWindow().activate(null, true)
        return true
    }

    private fun activate(id: String): Boolean {
        val window = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return false
        window.activate(null, true)
        return true
    }

    private fun terminal(tab: String): Boolean {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL) ?: return false
        val manager = window.contentManager
        if (tab.isNotEmpty()) manager.contents.firstOrNull { it.displayName == tab }?.let { manager.setSelectedContent(it) }
        window.activate(null, true)
        return true
    }

    private fun run(name: String): Boolean {
        val manager = RunContentManager.getInstance(project)
        val descriptor = manager.allDescriptors.lastOrNull { it.displayName == name } ?: return activate(ToolWindowId.RUN)
        manager.toFrontRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        return true
    }

    private fun problems(tab: String): Boolean {
        val window = ProblemsViewToolWindowUtils.getToolWindow(project) ?: return false
        if (tab.isEmpty()) window.activate(null, true) else ProblemsViewToolWindowUtils.selectTab(project, tab)
        return true
    }

    private fun diff(file: String): Boolean {
        val base = project.basePath ?: return false
        val path = Path.of(base).resolve(file).normalize().toString()
        if (!DiffPresenter.isWithinRoot(path, base)) return false
        val changes = ChangeListManager.getInstance(project).allChanges.filter { change ->
            val changed = (change.afterRevision ?: change.beforeRevision)?.file?.path ?: return@filter false
            changed == path || changed.startsWith("$path/")
        }
        if (changes.isEmpty()) return activate(ToolWindowId.COMMIT)
        ShowDiffAction.showDiffForChange(project, changes)
        return true
    }

    private fun action(id: String): Boolean {
        val action = ActionManager.getInstance().getAction(id) ?: return false
        ActionManager.getInstance().tryToExecute(action, null, null, ActionPlaces.TOOLWINDOW_CONTENT, true)
        return true
    }

    private companion object {
        const val TERMINAL = "Terminal"
    }
}
