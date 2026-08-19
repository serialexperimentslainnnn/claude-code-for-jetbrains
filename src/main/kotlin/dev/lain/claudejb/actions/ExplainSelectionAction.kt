package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.ClaudeToolWindowFactory

class ExplainSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: return

        val vFile: VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val relativePath = vFile?.let { relativize(it.path, project.basePath) } ?: "the current file"
        val lang = vFile?.extension?.lowercase() ?: ""

        val prompt = buildString {
            append("Explain this code from `").append(relativePath).append("`:\n\n")
            append("```").append(lang).append('\n')
            append(selection)
            if (!selection.endsWith("\n")) append('\n')
            append("```")
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ClaudeToolWindowFactory.TOOL_WINDOW_ID)
        if (toolWindow == null) {
            ChatSessionManager.getInstance(project).activeOrCreate().send(prompt)
            return
        }
        toolWindow.activate {
            val session = ClaudeToolWindowFactory.activePanel(project)?.session
                ?: ChatSessionManager.getInstance(project).activeOrCreate()
            session.send(prompt)
        }
    }

    override fun update(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection && e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun relativize(path: String, basePath: String?): String {
        if (basePath != null && path.startsWith(basePath)) {
            return path.removePrefix(basePath).trimStart('/')
        }
        return path.substringAfterLast('/')
    }
}
