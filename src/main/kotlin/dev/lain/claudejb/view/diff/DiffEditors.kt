package dev.lain.claudejb.view.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.model.diff.DiffPresenter
import kotlinx.serialization.json.JsonObject
import java.io.File

object DiffEditors {

    fun openDiff(project: Project, toolName: String, input: JsonObject, currentSnapshot: String? = null): VirtualFile? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        val file = File(path)
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposed = DiffPresenter.proposedContent(toolName, input, current) ?: return null

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        val title = DiffPresenter.diffTitle(file.name)
        val request = SimpleDiffRequest(
            title,
            factory.create(project, current, fileType),
            factory.create(project, proposed, fileType),
            "Current: ${file.name}",
            "Proposed by Claude",
        )
        val vFile = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        FileEditorManager.getInstance(project).openFile(vFile, false)
        OpenedDiffsService.getInstance(project).register(vFile)
        return vFile
    }

    data class TextSide(val label: String, val text: String)

    fun openTextDiff(project: Project, path: String, base: TextSide, current: TextSide): VirtualFile? {
        val name = File(path).name.ifBlank { return null }
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val factory = DiffContentFactory.getInstance()
        val title = DiffPresenter.diffTitle(name)
        val request = SimpleDiffRequest(
            title,
            factory.create(project, base.text, fileType),
            factory.create(project, current.text, fileType),
            base.label,
            current.label,
        )
        val vFile = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        FileEditorManager.getInstance(project).openFile(vFile, false)
        OpenedDiffsService.getInstance(project).register(vFile)
        return vFile
    }

    data class ReviewDiff(
        val file: VirtualFile,
        val proposed: Document,
        val currentText: String,
        val originalProposed: String,
    )

    fun openReviewDiff(project: Project, toolName: String, input: JsonObject, currentSnapshot: String? = null): ReviewDiff? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        val file = File(path)
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposedText = DiffPresenter.proposedContent(toolName, input, current) ?: return null
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        val proposedDoc = factory.createEditable(project, proposedText, fileType)
        val title = DiffPresenter.diffTitle(file.name)
        val request = SimpleDiffRequest(
            title,
            factory.create(project, current, fileType),
            proposedDoc,
            "Current: ${file.name}",
            "Proposed by Claude — edit before accepting",
        )
        val vFile = ChainDiffVirtualFile(SimpleDiffRequestChain(request), title)
        val opened = runCatching { FileEditorManager.getInstance(project).openFile(vFile, false) }.isSuccess
        if (!opened) return null
        OpenedDiffsService.getInstance(project).register(vFile)
        return ReviewDiff(vFile, proposedDoc.document, current, proposedText)
    }

    fun closeDiff(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        if (manager.isFileOpen(file)) manager.closeFile(file)
        OpenedDiffsService.getInstance(project).unregister(file)
    }
}
