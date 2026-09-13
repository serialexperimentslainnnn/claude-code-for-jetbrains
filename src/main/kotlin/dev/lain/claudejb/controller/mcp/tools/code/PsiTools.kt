package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PsiTools(private val project: Project, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "psi",
        "The IDE's syntax tree of a file: the tree to a depth, the element at a position with its parents, and edits that " +
            "replace or insert elements through the PSI so the IDE reformats and re-resolves them",
        listOf(Tool(PSI_TREE, ::tree), Tool(PSI_AT, ::at), Tool(PSI_REPLACE, ::replace), Tool(PSI_INSERT, ::insert)),
    )

    private suspend fun tree(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val depth = args.int("depth", DEFAULT_DEPTH)
        val max = args.int("max", DEFAULT_MAX)
        val rows = smartReadAction(project) {
            val psiFile = Locations.psiFile(project, path)
            val root = args.optionalString("line")?.let { elementAt(psiFile, args) } ?: psiFile
            Walk(depth, max, psiFile).also { it.visit(root, 0) }.out
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("truncated", rows.size >= max)
                put("elements", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private inner class Walk(private val depth: Int, private val max: Int, private val file: PsiFile) {
        val out = ArrayList<JsonObject>()

        fun visit(element: PsiElement, level: Int) {
            if (out.size >= max) return
            out += row(element, level, file)
            if (level >= depth) return
            element.children.filterNot { it is PsiWhiteSpace }.forEach { visit(it, level + 1) }
        }
    }

    private fun row(element: PsiElement, level: Int, file: PsiFile): JsonObject = buildJsonObject {
        val document = file.viewProvider.document
        put("level", level)
        put("type", element.node?.elementType?.toString() ?: element.javaClass.simpleName)
        put("class", element.javaClass.simpleName)
        put("line", document?.getLineNumber(element.textRange.startOffset)?.plus(1) ?: 0)
        put("length", element.textLength)
        put("text", element.text.lineSequence().firstOrNull()?.trim()?.take(TEXT_CHARS) ?: "")
    }

    private suspend fun at(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val rows = smartReadAction(project) {
            val psiFile = Locations.psiFile(project, path)
            val leaf = elementAt(psiFile, args)
            generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }.mapIndexed { index, e -> row(e, index, psiFile) }.toList()
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("parents", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun elementAt(psiFile: PsiFile, args: ToolArgs): PsiElement {
        val position = Locations.locate(project, args)
        return psiFile.findElementAt(position.offset) ?: throw ToolException("nothing at that position")
    }

    private suspend fun replace(args: ToolArgs): ToolResult = edit(args, insertion = null)

    private suspend fun insert(args: ToolArgs): ToolResult {
        val where = args.optionalString("where") ?: "after"
        if (where != "before" && where != "after") throw ToolException("where must be before or after")
        return edit(args, insertion = where)
    }

    private suspend fun edit(args: ToolArgs, insertion: String?): ToolResult {
        val path = args.string("path")
        val text = args.string("text")
        val levels = args.int("parent", 0)
        val (file, target) = smartReadAction(project) {
            val psiFile = Locations.psiFile(project, path)
            var element = elementAt(psiFile, args)
            repeat(levels) { element = element.parent?.takeIf { it !is PsiFile } ?: element }
            psiFile to element
        }
        val outcome = writeCommandAction(project, "Claude: psi ${insertion ?: "replace"} in ${file.name}") {
            val name = "fragment." + (file.virtualFile?.extension ?: "txt")
            val fragment = PsiFileFactory.getInstance(project).createFileFromText(name, file.language, text)
            val nodes = fragment.children.filter { it !is PsiWhiteSpace && it.textLength > 0 }
            val placed = if (nodes.isNotEmpty() && !PsiTreeUtil.hasErrorElements(fragment)) {
                place(target, nodes, insertion).also { CodeStyleManager.getInstance(project).reformat(it.parent ?: it) }
            } else {
                placeAsText(file, target, text, insertion)
            }
            row(placed, 0, file)
        }
        file.virtualFile?.let { reveal.file(it) }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("operation", insertion ?: "replace")
                put("element", outcome)
            },
        )
    }

    private fun placeAsText(file: PsiFile, target: PsiElement, text: String, insertion: String?): PsiElement {
        val document = file.viewProvider.document ?: throw ToolException("${file.name} has no document")
        val range = target.textRange
        val start = if (insertion == "after") range.endOffset else range.startOffset
        if (insertion == null) document.replaceString(range.startOffset, range.endOffset, text) else document.insertString(start, text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        CodeStyleManager.getInstance(project).reformatText(file, start, start + text.length)
        return file.findElementAt(start)?.let { leaf -> leaf.parent?.takeIf { it.textRange.startOffset == start } ?: leaf } ?: file
    }

    private fun place(target: PsiElement, nodes: List<PsiElement>, insertion: String?): PsiElement {
        if (insertion == "before") {
            nodes.forEach { target.parent.addBefore(it, target) }
            return target
        }
        val first = if (insertion == null) target.replace(nodes.first()) else target
        var anchor = first
        (if (insertion == null) nodes.drop(1) else nodes).forEach { anchor = anchor.parent.addAfter(it, anchor) }
        return if (insertion == null) first else anchor
    }

    companion object {

        private const val DEFAULT_DEPTH = 3
        private const val DEFAULT_MAX = 300
        private const val TEXT_CHARS = 80

        private val PATH = Param("path", "File path, absolute or relative to the project root")
        private val LINE = Param("line", "1-based line", type = "integer")
        private val COLUMN = Param("column", "1-based column (default 1)", type = "integer", required = false)
        private val PARENT = Param(
            "parent",
            "How many parents above the leaf to act on (default 0: the leaf)",
            type = "integer",
            required = false,
        )

        val PSI_TREE = ToolSpec(
            "psi_tree",
            "The PSI tree of a file, or of the element at a line, to a depth: level, element type, class, line, length and " +
                "the first line of text of each element.",
            listOf(
                PATH,
                Param("line", "1-based line whose element is the root (default: the whole file)", type = "integer", required = false),
                COLUMN,
                Param("depth", "Levels below the root (default $DEFAULT_DEPTH)", type = "integer", required = false),
                Param("max", "Maximum elements (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val PSI_AT = ToolSpec(
            "psi_at",
            "The PSI leaf at a position and every parent up to the file, innermost first: what the IDE sees there.",
            listOf(PATH, LINE, COLUMN),
        )

        val PSI_REPLACE = ToolSpec(
            "psi_replace",
            "Replaces the PSI element at a position (or a parent of it) with text parsed in the file's language, through the " +
                "PSI, then reformats: the IDE keeps references and structure consistent. One undoable command.",
            listOf(PATH, LINE, COLUMN, PARENT, Param("text", "The replacement, parsed in the file's language")),
            mutates = true,
        )

        val PSI_INSERT = ToolSpec(
            "psi_insert",
            "Inserts text parsed in the file's language before or after the PSI element at a position (or a parent of it), " +
                "through the PSI, then reformats. One undoable command.",
            listOf(
                PATH,
                LINE,
                COLUMN,
                PARENT,
                Param("text", "The text to insert, parsed in the file's language"),
                Param("where", "before or after (default after)", required = false),
            ),
            mutates = true,
        )
    }
}
