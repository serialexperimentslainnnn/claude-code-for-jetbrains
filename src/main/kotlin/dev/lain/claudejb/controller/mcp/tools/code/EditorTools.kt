package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class EditorTools(private val project: Project, private val reveal: Reveal, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "editor",
        "What the editor shows and whether the index is ready: open a file at a line, the active file and caret, indexing " +
            "state, and the Code menu's editing actions at a position",
        listOf(
            Tool(OPEN_FILE) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::openOne)) },
            Tool(ACTIVE_FILE, ::activeFile),
            Tool(INDEX_STATUS, ::indexStatus),
            Tool(EDITOR_ACTION, ::editorAction),
        ),
    )

    private suspend fun editorAction(args: ToolArgs): ToolResult {
        val name = args.string("action")
        val id = EDITOR_ACTIONS[name] ?: throw ToolException("action must be one of ${EDITOR_ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args, preview = false)
        if (target.path == null) throw ToolException("editor_action needs path")
        actions.dispatch(id, target)
        return ToolResult.toon(
            buildJsonObject {
                put("action", name)
                put("id", id)
                put("path", target.path)
                put("line", target.line)
                put("column", target.column)
                put("dispatched", true)
            },
        )
    }

    private suspend fun openOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val line = args.int("line", 1)
        val column = args.int("column", 1)
        if (line < 1 || column < 1) throw ToolException("line and column start at 1")
        val file = readAction { Locations.file(project, path) }
        val opened = reveal.file(file, line, column)
        return buildJsonObject {
            put("path", path)
            put("line", line)
            put("column", column)
            put("opened", opened)
        }
    }

    private suspend fun activeFile(ignored: ToolArgs): ToolResult = withContext(Dispatchers.EDT) {
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val caret = editor?.caretModel?.logicalPosition
        ToolResult.toon(
            buildJsonObject {
                put("file", file?.let { Locations.relative(project, it) } ?: "")
                put("line", caret?.line?.plus(1) ?: 0)
                put("column", caret?.column?.plus(1) ?: 0)
                put("selected", editor?.selectionModel?.selectedText?.take(SELECTION_CHARS) ?: "")
                put("open", buildJsonArray { manager.openFiles.forEach { add(JsonPrimitive(Locations.relative(project, it))) } })
            },
        )
    }

    private suspend fun indexStatus(args: ToolArgs): ToolResult {
        val wait = args.boolean("wait", false)
        val dumb = DumbService.getInstance(project).isDumb
        val indexing = if (dumb && wait) smartReadAction(project) { DumbService.getInstance(project).isDumb } else dumb
        return ToolResult.toon(
            buildJsonObject {
                put("indexing", indexing)
                put("waited", dumb && wait)
            },
        )
    }

    companion object {

        private const val SELECTION_CHARS = 200

        val OPEN_FILE = ToolSpec(
            "open_file",
            "Opens a file in an editor tab and places its caret at a line and column, as the IDE's Go to File does, " +
                "without taking the focus from where the user is working.",
            listOf(
                Param("path", "File path, absolute or relative to the project root", required = false),
                Batch.paths("each opens in its own tab"),
                Param("line", "1-based line for the caret (default 1)", type = "integer", required = false),
                Param("column", "1-based column for the caret (default 1)", type = "integer", required = false),
            ),
        )

        val ACTIVE_FILE = ToolSpec(
            "active_file",
            "The file in the selected editor with its caret position and selection, plus every open file. " +
                "Empty fields when no text editor is selected.",
        )

        val EDITOR_ACTIONS: Map<String, String> = linkedMapOf(
            "override" to "OverrideMethods",
            "implement" to "ImplementMethods",
            "delegate" to "DelegateMethods",
            "generate" to "Generate",
            "surround" to "SurroundWith",
            "unwrap" to "Unwrap",
            "comment_line" to "CommentByLineComment",
            "comment_block" to "CommentByBlockComment",
            "move_statement_up" to "MoveStatementUp",
            "move_statement_down" to "MoveStatementDown",
            "move_element_left" to "MoveElementLeft",
            "move_element_right" to "MoveElementRight",
            "move_line_up" to "MoveLineUp",
            "move_line_down" to "MoveLineDown",
            "rearrange" to "RearrangeCode",
            "auto_indent" to "AutoIndentLines",
            "insert_template" to "InsertLiveTemplate",
            "save_template" to "SaveAsTemplate",
            "fold" to "CollapseRegion",
            "unfold" to "ExpandRegion",
            "fold_recursively" to "CollapseRegionRecursively",
            "unfold_recursively" to "ExpandRegionRecursively",
            "fold_all" to "CollapseAllRegions",
            "unfold_all" to "ExpandAllRegions",
            "update_copyright" to "UpdateCopyright",
            "quick_doc" to "QuickJavaDoc",
            "quick_definition" to "QuickImplementations",
            "quick_type" to "QuickTypeDefinition",
        )

        val EDITOR_ACTION = ToolSpec(
            "editor_action",
            "Performs one of the Code menu's editing actions at a position of a file, exactly as the editor would with the " +
                "caret there: override, implement, delegate, generate, surround, unwrap, comment_line, comment_block, " +
                "move_statement_up/down, move_element_left/right, move_line_up/down, rearrange, auto_indent, " +
                "insert_template, save_template, fold, unfold, fold_recursively, unfold_recursively, fold_all, unfold_all, " +
                "update_copyright, quick_doc, quick_definition, quick_type. The file opens in a tab without focus; an " +
                "action that shows a chooser or a popup leaves it for the user.",
            listOf(
                Param("action", "One of the names above"),
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line for the caret (default 1)", type = "integer", required = false),
                Param("column", "1-based column for the caret (default 1)", type = "integer", required = false),
            ),
            mutates = true,
        )

        val INDEX_STATUS = ToolSpec(
            "index_status",
            "Whether the IDE is still indexing; with wait, returns once indexing finishes so symbol tools can be trusted.",
            listOf(Param("wait", "true to wait for indexing to finish (default false)", type = "boolean", required = false)),
        )
    }
}
