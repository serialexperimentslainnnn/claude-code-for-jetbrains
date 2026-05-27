package dev.lain.claudejb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.session.ChatSessionManager

/**
 * Editor-popup action: send the current selection to Claude with an "explain this code" prompt, then focus the chat.
 * The prompt carries the file's project-relative path and (when detectable) a language hint so the agent can fence
 * the snippet correctly. Enabled only when there is a non-empty selection in the focused editor.
 */
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

        val session = ChatSessionManager.getInstance(project).activeOrCreate()
        session.send(prompt)
        ToolWindowManager.getInstance(project).getToolWindow("Claude Code")?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection && e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** Project-relative path when [path] is under [basePath]; otherwise the bare file name. */
    private fun relativize(path: String, basePath: String?): String {
        if (basePath != null && path.startsWith(basePath)) {
            return path.removePrefix(basePath).trimStart('/')
        }
        return path.substringAfterLast('/')
    }
}
