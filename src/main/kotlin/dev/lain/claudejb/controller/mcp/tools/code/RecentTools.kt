package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.IdeActions
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RecentTools(private val project: Project, private val actions: IdeActions) {

    private val schemes = Schemes()

    fun domain(): ToolDomain = ToolDomain(
        "recent",
        "Where the user has been: recent and recently changed files, the editor's back and forward history, the " +
            "clipboard against a file, and the IDE's schemes",
        listOf(Tool(RECENT, ::recent), Tool(NAVIGATE_HISTORY, ::navigate), Tool(COMPARE_CLIPBOARD, ::compare), Tool(SCHEME, ::scheme)),
    )

    private suspend fun recent(args: ToolArgs): ToolResult {
        val kind = args.optionalString("kind") ?: "files"
        val max = args.int("max", DEFAULT_MAX)
        val files = withContext(Dispatchers.EDT) {
            when (kind) {
                "files" -> EditorHistoryManager.getInstance(project).fileList.asReversed()
                "changed_files" -> IdeDocumentHistory.getInstance(project).changedFiles.asReversed()
                else -> throw ToolException("kind must be files or changed_files")
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("kind", kind)
                put("count", files.size)
                put("truncated", files.size > max)
                put("files", buildJsonArray { files.take(max).forEach { add(JsonPrimitive(Locations.relative(project, it))) } })
            },
        )
    }

    private suspend fun navigate(args: ToolArgs): ToolResult {
        val direction = args.string("direction")
        if (direction !in DIRECTIONS) throw ToolException("direction must be one of ${DIRECTIONS.joinToString()}")
        val moved = withContext(Dispatchers.EDT) {
            val history = IdeDocumentHistory.getInstance(project)
            FocusKeeper.keeping(project) {
                when (direction) {
                    "back" -> history.isBackAvailable.also { if (it) history.back() }
                    "forward" -> true.also { history.forward() }
                    "last_change" -> true.also { history.navigatePreviousChange() }
                    else -> true.also { history.navigateNextChange() }
                }
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("direction", direction)
                put("moved", moved)
            },
        )
    }

    private suspend fun compare(args: ToolArgs): ToolResult {
        val target = TargetContext.target(args)
        if (target.path == null) throw ToolException("compare_clipboard needs path")
        actions.dispatch(COMPARE_CLIPBOARD_ACTION, target)
        return ToolResult.toon(
            buildJsonObject {
                put("path", target.path)
                put("dispatched", true)
            },
        )
    }

    private suspend fun scheme(args: ToolArgs): ToolResult {
        val kind = args.string("kind")
        val wanted = schemeToSet(args)
        if (kind !in Schemes.KINDS) throw ToolException("kind must be one of ${Schemes.KINDS.joinToString()}")
        val listed = withContext(Dispatchers.EDT) {
            if (wanted != null) FocusKeeper.keeping(project) { schemes.set(kind, wanted) }
            schemes.list(kind)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("kind", kind)
                put("current", listed.current)
                put("count", listed.names.size)
                put("schemes", buildJsonArray { listed.names.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private fun schemeToSet(args: ToolArgs): String? = when (args.optionalString("action") ?: "list") {
        "list" -> null
        "set" -> args.optionalString("name")?.takeIf { it.isNotBlank() } ?: throw ToolException("action=set needs name")
        else -> throw ToolException("action must be list or set")
    }

    companion object {

        private const val DEFAULT_MAX = 50
        private const val COMPARE_CLIPBOARD_ACTION = "CompareClipboardWithSelection"

        val DIRECTIONS: List<String> = listOf("back", "forward", "last_change", "next_change")

        val RECENT = ToolSpec(
            "recent",
            "The files the user opened most recently (kind=files, as View ▸ Recent Files lists them) or changed most " +
                "recently in this session (kind=changed_files), newest first.",
            listOf(
                Param("kind", "files (default) or changed_files", required = false),
                Param("max", "Maximum files to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val NAVIGATE_HISTORY = ToolSpec(
            "navigate_history",
            "Moves the user's editor through the IDE's navigation history, as Navigate ▸ Back, Forward, Last Edit " +
                "Location and Next Edit Location do: back, forward, last_change or next_change.",
            listOf(Param("direction", "back, forward, last_change or next_change")),
            mutates = true,
        )

        val COMPARE_CLIPBOARD = ToolSpec(
            "compare_clipboard",
            "Opens the IDE's diff of the clipboard against a file, as View ▸ Compare with Clipboard does, without taking " +
                "the focus.",
            listOf(Param("path", "File path, absolute or relative to the project root")),
        )

        val SCHEME = ToolSpec(
            "scheme",
            "Lists or sets one of the IDE's schemes, as View ▸ Quick Switch Scheme does: kind is theme, color, keymap or " +
                "code_style; action=list returns the names with the current one, action=set applies name.",
            listOf(
                Param("kind", "theme, color, keymap or code_style"),
                Param("action", "list (default) or set", required = false),
                Param("name", "The scheme to apply (action=set)", required = false),
            ),
            mutates = true,
        )
    }
}
