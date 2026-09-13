package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.DocumentUtil
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FormatTools(private val project: Project, private val io: CoroutineDispatcher = Dispatchers.IO) {

    fun domain(): ToolDomain = ToolDomain(
        "format",
        "The IDE's Reformat Code and Optimize Imports on one file, respecting .editorconfig and the project code style",
        listOf(
            Tool(REFORMAT) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::reformatOne)) },
            Tool(OPTIMIZE_IMPORTS) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::optimizeOne)) },
        ),
    )

    private suspend fun reformatOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val fromLine = args.int("from_line", 0)
        val toLine = args.int("to_line", 0)
        val (psiFile, document) = readAction { open(path) }
        val range = readAction { range(document, fromLine, toLine) }
        val changed = process(document) { ReformatCodeProcessor(project, psiFile, range, false) }
        return buildJsonObject {
            put("path", path)
            put("from_line", fromLine)
            put("to_line", toLine)
            put("changed", changed)
        }
    }

    private suspend fun optimizeOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val (psiFile, document) = readAction { open(path) }
        val changed = process(document) { OptimizeImportsProcessor(project, psiFile) }
        return buildJsonObject {
            put("path", path)
            put("changed", changed)
        }
    }

    private fun open(path: String): Pair<PsiFile, Document> {
        val psiFile = Locations.psiFile(project, path)
        return psiFile to (psiFile.viewProvider.document ?: throw ToolException("$path has no document"))
    }

    private fun range(document: Document, fromLine: Int, toLine: Int): TextRange? {
        if (fromLine == 0 && toLine == 0) return null
        if (fromLine < 1 || toLine < fromLine || !DocumentUtil.isValidLine(toLine - 1, document)) {
            throw ToolException("from_line and to_line must both be given, 1 <= from_line <= to_line <= ${document.lineCount}")
        }
        return TextRange(document.getLineStartOffset(fromLine - 1), document.getLineEndOffset(toLine - 1))
    }

    private suspend fun process(document: Document, processor: () -> AbstractLayoutCodeProcessor): Boolean {
        val stamp = document.modificationStamp
        withContext(io) { processor().runWithoutProgress() }
        edtWriteAction { FileDocumentManager.getInstance().saveDocument(document) }
        return document.modificationStamp != stamp
    }

    companion object {

        val REFORMAT = ToolSpec(
            "reformat",
            "Runs the IDE's Reformat Code on one file, or on a line range, with the project's code style and .editorconfig.",
            listOf(
                Param("path", "File path, absolute or relative to the project root", required = false),
                Batch.paths("whole files only"),
                Param("from_line", "First 1-based line to reformat (default: the whole file)", type = "integer", required = false),
                Param("to_line", "Last 1-based line to reformat; required with from_line", type = "integer", required = false),
            ),
            mutates = true,
        )

        val OPTIMIZE_IMPORTS = ToolSpec(
            "optimize_imports",
            "Runs the IDE's Optimize Imports on one file; changed is false for languages without an import optimizer.",
            listOf(Param("path", "File path, absolute or relative to the project root", required = false), Batch.paths("one row each")),
            mutates = true,
        )
    }
}
