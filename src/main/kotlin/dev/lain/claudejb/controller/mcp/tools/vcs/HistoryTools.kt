package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.controller.mcp.tools.code.Locations
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class HistoryTools(
    private val project: Project,
    private val actions: IdeActions,
    private val reveal: Reveal,
    private val git: GitCommands = GitCommands(project),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val labels = LinkedHashMap<String, Label>()

    fun domain(): ToolDomain = ToolDomain(
        "history",
        "A file's past: who wrote each line, its commits, its local history, and its content at a ref",
        listOf(Tool(BLAME, ::blame), Tool(FILE_HISTORY, ::fileHistory), Tool(LOCAL_HISTORY, ::localHistory), Tool(FILE_AT, ::fileAt)),
    )

    private suspend fun blame(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val from = args.int("from", 1)
        val to = args.int("to", Int.MAX_VALUE)
        if (from < 1 || to < from) throw ToolException("from starts at 1 and to is not before it")
        val file = readAction { ReadTools.resolveFile(project, path) }
        val rows = withContext(io) { annotate(file, from, to) }
        if (reveal.mirroring) actions.dispatch(ANNOTATE, TargetContext.Target(path, from, 1, null, null))
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("count", rows.size)
                put("lines", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private fun annotate(file: VirtualFile, from: Int, to: Int) = vcs {
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
            ?: throw ToolException("${file.name} is not under version control")
        val provider = vcs.annotationProvider ?: throw ToolException("${vcs.displayName} offers no annotations")
        val annotation = provider.annotate(file)
        val author = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.AUTHOR }
        (from - 1 until minOf(to, annotation.lineCount)).map { line ->
            buildJsonObject {
                put("line", line + 1)
                put("hash", annotation.getLineRevisionNumber(line)?.asString() ?: "")
                put("author", author?.getValue(line) ?: "")
                put("date", annotation.getLineDate(line)?.toInstant()?.toString() ?: "")
            }
        }
    }

    private suspend fun fileHistory(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val max = args.int("max", DEFAULT_MAX)
        val file = readAction { ReadTools.resolveFile(project, path) }
        val relative = Locations.relative(project, file)
        val commits = withContext(io) { project.service<GitHistoryService>().fileHistory(relative, max) }
        if (reveal.mirroring) reveal.fileHistory(file.path)
        return ToolResult.toon(
            buildJsonObject {
                put("path", relative)
                put("count", commits.size)
                put("commits", buildJsonArray { commits.forEach { add(GitReadTools.commitRowOf(it)) } })
            },
        )
    }

    private suspend fun localHistory(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val action = args.optionalString("action") ?: "show"
        val label = args.optionalString("label")
        val file = readAction { ReadTools.resolveFile(project, path) }
        when (action) {
            "show" -> actions.dispatch(SHOW_LOCAL_HISTORY, TargetContext.Target(path, 1, 1, null, null))
            "label" -> labels[named(action, label)] = LocalHistory.getInstance().putSystemLabel(project, named(action, label))
            "revert" -> withContext(Dispatchers.EDT) { FocusKeeper.keeping(project) { known(named(action, label)).revert(project, file) } }
            else -> throw ToolException("action must be show, label or revert")
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("action", action)
                put("label", label ?: "")
                put("labels", buildJsonArray { labels.keys.forEach { add(buildJsonObject { put("name", it) }) } })
            },
        )
    }

    private fun named(action: String, label: String?): String = label ?: throw ToolException("action=$action needs label")

    private fun known(label: String): Label = labels[label] ?: throw ToolException("no label named $label was put in this session")

    private suspend fun fileAt(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val ref = args.string("ref")
        val file = readAction { ReadTools.resolveFile(project, path) }
        val relative = Locations.relative(project, file)
        val lines = withContext(io) { git.show(ref, relative) }
        val text = lines.joinToString("\n")
        if (reveal.mirroring) FocusKeeper.keep(project) { showDiff(file, ref, text) }
        return ToolResult.toon(
            buildJsonObject {
                put("path", relative)
                put("ref", ref)
                put("lines", lines.size)
                put("text", text)
            },
        )
    }

    private fun showDiff(file: VirtualFile, ref: String, text: String) {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "${file.name}: $ref vs working tree",
            factory.create(project, text, file),
            factory.create(project, file),
            ref,
            "Working tree",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun <T> vcs(block: () -> T): T = try {
        block()
    } catch (e: VcsException) {
        throw ToolException(e.message.ifBlank { "the VCS refused" }, e)
    }

    companion object {

        private const val DEFAULT_MAX = 50
        private const val ANNOTATE = "Annotate"
        private const val SHOW_LOCAL_HISTORY = "LocalHistory.ShowHistory"

        private val PATH = Param("path", "File path, absolute or relative to the project root")

        val BLAME = ToolSpec(
            "blame",
            "Who wrote each line of a file and when, from the IDE's annotations (git blame): line, commit, author and " +
                "date, for the lines from..to. The annotation gutter is shown on the file.",
            listOf(
                PATH,
                Param("from", "First line, 1-based (default 1)", type = "integer", required = false),
                Param("to", "Last line, inclusive (default: the end)", type = "integer", required = false),
            ),
        )

        val FILE_HISTORY = ToolSpec(
            "file_history",
            "The commits that touched a file, renames followed, newest first, as Git ▸ Show History lists them; the " +
                "history tab is shown.",
            listOf(PATH, Param("max", "Maximum commits (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val LOCAL_HISTORY = ToolSpec(
            "local_history",
            "The IDE's Local History of a file: show opens its history view; label puts a named label on the whole " +
                "project's local history (a restore point before a risky change); revert brings the file back to a label " +
                "put in this session.",
            listOf(
                PATH,
                Param("action", "show (default), label or revert", required = false),
                Param("label", "The label name (label, revert)", required = false),
            ),
            mutates = true,
        )

        val FILE_AT = ToolSpec(
            "file_at",
            "A file's content at a ref (a branch, a tag or a commit), as git show ref:path gives it, and the IDE's diff " +
                "of that version against the working tree.",
            listOf(PATH, Param("ref", "The branch, tag or commit")),
        )
    }
}
