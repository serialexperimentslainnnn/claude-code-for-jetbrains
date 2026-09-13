package dev.lain.claudejb.controller.mcp

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.controller.git.ForgeViewNavigator
import dev.lain.claudejb.controller.git.GitLogNavigator
import dev.lain.claudejb.model.settings.ClaudeSettings

internal class Reveal(private val project: Project) {

    val mirroring: Boolean
        get() = ClaudeSettings.getInstance(project).state.ideMcp.mirror

    suspend fun file(file: VirtualFile, line: Int = 1, column: Int = 1, preview: Boolean = false): Boolean =
        FocusKeeper.keep(project) {
            val descriptor = OpenFileDescriptor(project, file, line - 1, column - 1).setUsePreviewTab(preview)
            val manager = FileEditorManager.getInstance(project)
            manager.openTextEditor(descriptor, false) != null || manager.openFile(file, false).isNotEmpty()
        }

    suspend fun toolWindow(id: String): Boolean = FocusKeeper.keep(project) { window(id)?.also { it.activate(null, false) } != null }

    suspend fun content(windowId: String, name: String): Boolean = FocusKeeper.keep(project) {
        val window = window(windowId) ?: return@keep false
        val manager = window.contentManager
        val content = manager.contents.firstOrNull { it.displayName == name } ?: return@keep false
        val userIsThere = window.isActive
        window.activate({ if (!userIsThere) manager.setSelectedContent(content, false) }, false)
        true
    }

    suspend fun log(): Boolean = FocusKeeper.keep(project) { GitLogNavigator.showLog(project, focus = false) }

    suspend fun commit(hash: String): Boolean = FocusKeeper.keep(project) { GitLogNavigator.showCommit(project, hash, focus = false) }

    suspend fun range(exclusive: String, inclusive: String): Boolean =
        FocusKeeper.keep(project) { GitLogNavigator.showRange(project, exclusive, inclusive, focus = false) }

    suspend fun fileHistory(path: String): Boolean = FocusKeeper.keep(project) { GitLogNavigator.showFileHistory(project, path) }

    suspend fun requests(): Boolean = FocusKeeper.keep(project) { ForgeViewNavigator.open(project, focus = false) }

    suspend fun problems(tab: String): Boolean = FocusKeeper.keep(project) {
        val window = ProblemsViewToolWindowUtils.getToolWindow(project) ?: return@keep false
        val content = if (tab.isEmpty()) null else ProblemsViewToolWindowUtils.getContentById(project, tab) ?: return@keep false
        window.activate({ if (content != null) window.contentManager.setSelectedContent(content, false) }, false)
        true
    }

    private fun window(id: String): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(id)
}
