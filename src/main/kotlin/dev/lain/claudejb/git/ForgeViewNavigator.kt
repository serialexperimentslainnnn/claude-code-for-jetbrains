package dev.lain.claudejb.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object ForgeViewNavigator {

    val TOOL_WINDOW_IDS: List<String> = listOf("Pull Requests", "Merge Requests")

    fun open(project: Project): Boolean {
        val toolWindow = found(project) ?: return false
        toolWindow.activate(null, true)
        return true
    }

    private fun found(project: Project) =
        TOOL_WINDOW_IDS.firstNotNullOfOrNull { ToolWindowManager.getInstance(project).getToolWindow(it) }
}
