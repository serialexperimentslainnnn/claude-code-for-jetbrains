package dev.lain.claudejb.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

object DiffPresenter {

    val REVIEWABLE_TOOLS = setOf("Edit", "Write", "MultiEdit")

    fun filePathOf(input: JsonObject): String? = input.str("file_path")

    fun isWithinRoot(path: String?, projectRoot: String?): Boolean {
        if (path == null || projectRoot == null) return false
        return try {
            val canonicalFile = File(path).canonicalFile
            val canonicalRoot = File(projectRoot).canonicalFile
            val rootPath = canonicalRoot.path
            val filePath = canonicalFile.path
            filePath == rootPath || filePath.startsWith(rootPath + File.separator)
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun proposedContent(toolName: String, input: JsonObject, currentText: String): String? = when (toolName) {
        "Write" -> input.str("content") ?: ""

        "Edit" -> applyEdit(currentText, input)

        "MultiEdit" -> {
            val edits = input["edits"] as? JsonArray ?: return null
            edits.fold(currentText) { acc, element -> applyEdit(acc, element.jsonObject) ?: acc }
        }

        else -> null
    }

    private fun applyEdit(text: String, edit: JsonObject): String? {
        val old = edit.str("old_string") ?: return null
        val new = edit.str("new_string") ?: ""
        val replaceAll = (edit["replace_all"] as? JsonPrimitive)?.booleanOrNull ?: false
        return if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
    }

    internal fun diffTitle(fileName: String) = "$fileName — Claude"

    fun openDiff(project: Project, toolName: String, input: JsonObject, currentSnapshot: String? = null): VirtualFile? {
        val path = filePathOf(input) ?: return null
        val file = File(path)
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposed = proposedContent(toolName, input, current) ?: return null

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        val title = diffTitle(file.name)
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
        val title = diffTitle(name)
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
        val path = filePathOf(input) ?: return null
        val file = File(path)
        val current = currentSnapshot ?: if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val proposedText = proposedContent(toolName, input, current) ?: return null
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val factory = DiffContentFactory.getInstance()
        val proposedDoc = factory.createEditable(project, proposedText, fileType)
        val title = diffTitle(file.name)
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

    fun computeHunks(current: String, proposed: String): List<Hunk> {
        val fragments = com.intellij.diff.comparison.ComparisonManager.getInstance()
            .compareLines(
                current,
                proposed,
                com.intellij.diff.comparison.ComparisonPolicy.DEFAULT,
                com.intellij.openapi.progress.DumbProgressIndicator.INSTANCE,
            )
        return fragments.map { Hunk(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
    }

    fun unifiedDiff(current: String, proposed: String, context: Int = 3): String {
        val hunks = computeHunks(current, proposed)
        if (hunks.isEmpty()) return ""
        val cur = current.split("\n")
        val pro = proposed.split("\n")
        val sb = StringBuilder()
        for (h in hunks) {
            val ctxStart = (h.start1 - context).coerceAtLeast(0)
            val ctxEnd = (h.end1 + context).coerceAtMost(cur.size)
            sb.append("@@ -${h.start1 + 1},${h.end1 - h.start1} +${h.start2 + 1},${h.end2 - h.start2} @@\n")
            for (i in ctxStart until h.start1) sb.append(' ').append(cur[i]).append('\n')
            for (i in h.start1 until h.end1) sb.append('-').append(cur.getOrElse(i) { "" }).append('\n')
            for (i in h.start2 until h.end2) sb.append('+').append(pro.getOrElse(i) { "" }).append('\n')
            for (i in h.end1 until ctxEnd) sb.append(' ').append(cur[i]).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    fun closeDiff(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        if (manager.isFileOpen(file)) manager.closeFile(file)
        OpenedDiffsService.getInstance(project).unregister(file)
    }
}

data class Hunk(
    val start1: Int,
    val end1: Int,
    val start2: Int,
    val end2: Int,
)
