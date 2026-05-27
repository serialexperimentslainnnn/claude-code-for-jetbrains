package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Pulls the current editor file/selection so the user can inject them as @-context, mirroring how the
 * CLI lets you reference files. All accessors must be called on the EDT.
 */
object EditorContextProvider {

    /** Absolute path of the file open in the active editor, or null. */
    fun currentFilePath(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        return vFile?.path
    }

    fun currentFileName(project: Project): String? =
        currentFilePath(project)?.substringAfterLast('/')

    /** Selected text in the active editor, or null if there is no selection. */
    fun currentSelection(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }
    }

    /** 1-based line where the current selection starts (or the caret line if nothing is selected), or null. */
    fun currentSelectionStartLine(project: Project): Int? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val offset = editor.selectionModel.selectionStart
        return editor.document.getLineNumber(offset) + 1
    }
}
