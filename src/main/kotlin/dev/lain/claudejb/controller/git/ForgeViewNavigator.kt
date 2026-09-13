package dev.lain.claudejb.controller.git

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import javax.swing.JList

object ForgeViewNavigator {

    val TOOL_WINDOW_IDS: List<String> = listOf("Pull Requests", "Merge Requests")

    fun open(project: Project, focus: Boolean): Boolean {
        val toolWindow = found(project) ?: return false
        toolWindow.activate(null, focus)
        return true
    }

    fun selectRequest(project: Project, number: Long): DataContext? {
        val toolWindow = found(project) ?: return null
        toolWindow.contentManager.contents.forEach { content ->
            UIUtil.findComponentsOfType(content.component, JList::class.java).forEach { list ->
                val index = (0 until list.model.size).firstOrNull { numberOf(list.model.getElementAt(it)) == number }
                if (index != null) {
                    list.selectedIndex = index
                    list.ensureIndexIsVisible(index)
                    return DataManager.getInstance().getDataContext(list)
                }
            }
        }
        return null
    }

    fun selectTimeline(project: Project, number: Long): Boolean {
        val manager = FileEditorManager.getInstance(project)
        val timeline = manager.openFiles.firstOrNull { !it.isInLocalFileSystem && it.name == "#$number" } ?: return false
        manager.openFile(timeline, false)
        return true
    }

    private fun numberOf(item: Any?): Long? {
        val target = item ?: return null
        return runCatching { target.javaClass.getMethod("getNumber").invoke(target) as? Number }.getOrNull()?.toLong()
    }

    private fun found(project: Project) =
        TOOL_WINDOW_IDS.firstNotNullOfOrNull { ToolWindowManager.getInstance(project).getToolWindow(it) }
}
