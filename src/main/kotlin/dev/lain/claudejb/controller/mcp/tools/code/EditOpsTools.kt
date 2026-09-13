package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.FocusKeeper
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class EditOpsTools(private val project: Project, private val reveal: Reveal, private val actions: IdeActions) {

    private val replace = TextReplace(project)

    fun domain(): ToolDomain = ToolDomain(
        "edit_ops",
        "The Edit menu on a file: undo and redo through the IDE's undo stack, replace across files, and the line " +
            "operations at a line",
        listOf(
            Tool(UNDO) { history(it, redo = false) },
            Tool(REDO) { history(it, redo = true) },
            Tool(SEARCH_REPLACE, ::searchReplace),
            Tool(LINE_OPS, ::lineOps),
        ),
    )

    private suspend fun history(args: ToolArgs, redo: Boolean): ToolResult {
        val path = args.string("path")
        val file = readAction { ReadTools.resolveFile(project, path) }
        reveal.file(file)
        val done = withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).getSelectedEditor(file)
            val undo = UndoManager.getInstance(project)
            val available = if (redo) undo.isRedoAvailable(editor) else undo.isUndoAvailable(editor)
            if (available) FocusKeeper.keeping(project) { if (redo) undo.redo(editor) else undo.undo(editor) }
            available
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put(if (redo) "redone" else "undone", done)
            },
        )
    }

    private suspend fun searchReplace(args: ToolArgs): ToolResult {
        val query = args.string("query")
        val replacement = args.string("replacement")
        val max = args.int("max", DEFAULT_MAX_FILES)
        val directory = readAction { args.optionalString("path")?.let { ReadTools.resolveDirectory(project, it).path } }
        val model = replace.model(query, replacement, args.boolean("regex", false), args.boolean("case_sensitive", false), directory)
        val explicit = args.strings("paths")
        val files = if (explicit.isEmpty()) {
            replace.filesMatching(model, max)
        } else {
            readAction { explicit.map { ReadTools.resolveFile(project, it) } }
        }
        val changes = files.map { replace.replaceIn(it, model) }.filter { it.replaced > 0 }
        changes.firstOrNull()?.let { reveal.file(it.file) }
        return ToolResult.toon(
            buildJsonObject {
                put("query", query)
                put("files", changes.size)
                put("replaced", changes.sumOf { it.replaced })
                put("truncated", explicit.isEmpty() && files.size >= max)
                put(
                    "changes",
                    buildJsonArray {
                        changes.forEach { change ->
                            add(
                                buildJsonObject {
                                    put("file", Locations.relative(project, change.file))
                                    put("replaced", change.replaced)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun lineOps(args: ToolArgs): ToolResult {
        val name = args.string("action")
        val id = LINE_ACTIONS[name] ?: throw ToolException("action must be one of ${LINE_ACTIONS.keys.joinToString()}")
        val target = TargetContext.target(args, preview = false)
        if (target.path == null) throw ToolException("line_ops needs path")
        actions.dispatch(id, target)
        return ToolResult.toon(
            buildJsonObject {
                put("action", name)
                put("path", target.path)
                put("line", target.line)
                put("dispatched", true)
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX_FILES = 50

        val LINE_ACTIONS: Map<String, String> = linkedMapOf(
            "join" to "EditorJoinLines",
            "duplicate" to "EditorDuplicate",
            "delete" to "EditorDeleteLine",
            "indent" to "EditorIndentLineOrSelection",
            "unindent" to "EditorUnindentSelection",
        )

        private val PATH = Param("path", "File path, absolute or relative to the project root")

        val UNDO = ToolSpec(
            "undo",
            "Undoes the last change of a file through the IDE's undo stack, exactly as Edit ▸ Undo would in that file's " +
                "editor; a change that spans other files asks the user first. Returns whether there was anything to undo.",
            listOf(PATH),
            mutates = true,
        )

        val REDO = ToolSpec(
            "redo",
            "Redoes the last undone change of a file through the IDE's undo stack, as Edit ▸ Redo would. Returns whether " +
                "there was anything to redo.",
            listOf(PATH),
            mutates = true,
        )

        val SEARCH_REPLACE = ToolSpec(
            "search_replace",
            "Replaces text or a regular expression across files, as Replace in Files would with Replace All: every " +
                "occurrence in the files that match (or only in paths), one undoable command per file, the first changed " +
                "file shown in the editor. Returns the files and the count per file.",
            listOf(
                Param("query", "Text or regular expression to find"),
                Param("replacement", "Replacement text; with regex, \$1 refers to a group"),
                Param("regex", "true to treat query as a regular expression (default false)", type = "boolean", required = false),
                Param("case_sensitive", "true to match case (default false)", type = "boolean", required = false),
                Param("path", "Directory to replace under, relative to the project root (default: whole project)", required = false),
                Batch.param(Batch.PATHS, "Only these files, all in one call, instead of every file that matches"),
                Param(
                    "max",
                    "Maximum files to change when paths is not given (default $DEFAULT_MAX_FILES)",
                    type = "integer",
                    required = false,
                ),
            ),
            mutates = true,
        )

        val LINE_OPS = ToolSpec(
            "line_ops",
            "One of the editor's line operations at a line of a file, as the Edit menu would with the caret there: join " +
                "(with the next line), duplicate, delete, indent or unindent. The file opens in a tab without focus.",
            listOf(
                Param("action", "join, duplicate, delete, indent or unindent"),
                PATH,
                Param("line", "1-based line to act on (default 1)", type = "integer", required = false),
            ),
            mutates = true,
        )
    }
}
