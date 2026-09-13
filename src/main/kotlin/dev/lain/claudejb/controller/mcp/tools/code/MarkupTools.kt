package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon

internal class MarkupTools(private val project: Project, private val targets: TargetContext, private val reveal: Reveal) {

    private class Mark(val id: Int, val file: VirtualFile, val kind: String, val line: Int, val tooltip: String, val dispose: () -> Unit)

    private val marks = LinkedHashMap<Int, Mark>()
    private val ids = AtomicInteger()

    fun domain(): ToolDomain = ToolDomain(
        "markup",
        "Marks Claude leaves in the editor for the user: highlighted ranges, gutter icons with a tooltip, inline hints; " +
            "each has an id to remove it, and all go when the session ends",
        listOf(Tool(MARK_ADD, ::add), Tool(MARK_REMOVE, ::remove), Tool(MARKS, ::list), Tool(HINT_ADD, ::hint)),
    )

    private suspend fun add(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val kind = args.optionalString("kind") ?: "highlight"
        val key = KINDS[kind] ?: throw ToolException("kind must be one of ${KINDS.keys.joinToString()}")
        val tooltip = args.optionalString("tooltip").orEmpty()
        val line = args.int("line", 1)
        val toLine = args.int("to_line", line)
        val file = readAction { Locations.file(project, path) }
        val document = readAction { Locations.document(project, file) }
        if (line < 1 || toLine < line || toLine > document.lineCount) throw ToolException("line..to_line must lie inside the file")
        val mark = withContext(Dispatchers.EDT) {
            val model = DocumentMarkupModel.forDocument(document, project, true)
            val start = document.getLineStartOffset(line - 1)
            val end = document.getLineEndOffset(toLine - 1)
            val highlighter: RangeHighlighter = if (kind == "gutter") {
                model.addLineHighlighter(null, line - 1, HighlighterLayer.SELECTION - 1).also { it.gutterIconRenderer = gutter(tooltip) }
            } else {
                model.addRangeHighlighter(key, start, end, HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE)
            }
            if (tooltip.isNotEmpty()) highlighter.errorStripeTooltip = tooltip
            Mark(ids.incrementAndGet(), file, kind, line, tooltip) { highlighter.dispose() }
        }
        marks[mark.id] = mark
        if (reveal.mirroring) reveal.file(file, line, preview = true)
        return ToolResult.toon(row(mark))
    }

    private fun gutter(tooltip: String): GutterIconRenderer = object : GutterIconRenderer() {
        override fun getIcon(): Icon = ICON
        override fun getTooltipText(): String = tooltip
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private suspend fun hint(args: ToolArgs): ToolResult {
        val text = args.string("text")
        val where = args.optionalString("where") ?: "after"
        if (where != "before" && where != "after") throw ToolException("where must be before or after")
        args.string("path")
        val target = TargetContext.target(args)
        val context = targets.of(target)
        val mark = withContext(Dispatchers.EDT) {
            val editor = CommonDataKeys.EDITOR.getData(context)
            val file = CommonDataKeys.VIRTUAL_FILE.getData(context)
            val inlay: Inlay<*>? = editor?.inlayModel?.addInlineElement(editor.caretModel.offset, where == "after", HintRenderer(text))
            if (editor == null || file == null || inlay == null) throw ToolException("no editor could take an inlay on ${target.path}")
            Mark(ids.incrementAndGet(), file, "hint", target.line, text) { inlay.dispose() }
        }
        marks[mark.id] = mark
        return ToolResult.toon(row(mark))
    }

    private suspend fun remove(args: ToolArgs): ToolResult {
        val id = args.int("id", 0)
        val mark = marks.remove(id) ?: throw ToolException("no mark with id $id; marks lists them")
        withContext(Dispatchers.EDT) { mark.dispose() }
        return ToolResult.toon(
            buildJsonObject {
                put("id", id)
                put("removed", true)
            },
        )
    }

    private suspend fun list(args: ToolArgs): ToolResult {
        val path = args.optionalString("path")
        val file = path?.let { readAction { Locations.file(project, it) } }
        val rows = marks.values.filter { file == null || it.file == file }.map(::row)
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put("marks", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun row(mark: Mark) = buildJsonObject {
        put("id", mark.id)
        put("path", Locations.relative(project, mark.file))
        put("kind", mark.kind)
        put("line", mark.line)
        put("tooltip", mark.tooltip)
    }

    companion object {

        private val ICON: Icon = com.intellij.icons.AllIcons.General.Information

        val KINDS: Map<String, TextAttributesKey> = linkedMapOf(
            "highlight" to EditorColors.SEARCH_RESULT_ATTRIBUTES,
            "warning" to CodeInsightColors.WARNINGS_ATTRIBUTES,
            "error" to CodeInsightColors.ERRORS_ATTRIBUTES,
            "gutter" to EditorColors.SEARCH_RESULT_ATTRIBUTES,
        )

        private val PATH = Param("path", "File path, absolute or relative to the project root")

        val MARK_ADD = ToolSpec(
            "mark_add",
            "Marks lines of a file for the user: kind=highlight (default), warning or error colour the range line..to_line " +
                "in every editor of the file, kind=gutter puts an icon with the tooltip in the gutter of line. Returns the " +
                "mark's id; the file is shown in the preview tab.",
            listOf(
                PATH,
                Param("line", "1-based first line (default 1)", type = "integer", required = false),
                Param("to_line", "1-based last line (default: line)", type = "integer", required = false),
                Param("kind", "highlight (default), warning, error or gutter", required = false),
                Param("tooltip", "Text shown on hover and in the error stripe", required = false),
            ),
            mutates = true,
        )

        val MARK_REMOVE = ToolSpec(
            "mark_remove",
            "Removes a mark or a hint by the id mark_add or hint_add returned.",
            listOf(Param("id", "The mark id", type = "integer")),
            mutates = true,
        )

        val MARKS = ToolSpec(
            "marks",
            "The marks and hints Claude left in this session, all or for one file: id, path, kind, line, tooltip.",
            listOf(Param("path", "Only the marks of this file (default: all)", required = false)),
        )

        val HINT_ADD = ToolSpec(
            "hint_add",
            "Adds an inline hint, as the IDE's parameter hints look, before or after a position of a file, in its editor; " +
                "returns the hint's id for mark_remove.",
            listOf(
                PATH,
                Param("line", "1-based line", type = "integer"),
                Param("column", "1-based column (default 1)", type = "integer", required = false),
                Param("text", "The hint text"),
                Param("where", "before or after the position (default after)", required = false),
            ),
            mutates = true,
        )
    }
}
