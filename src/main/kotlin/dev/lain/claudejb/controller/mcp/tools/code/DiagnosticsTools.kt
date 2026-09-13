package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.lain.claudejb.controller.mcp.Reveal
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DiagnosticsTools(
    private val project: Project,
    private val reveal: Reveal,
    private val daemon: DaemonHighlights = DaemonHighlights(project),
) {

    fun domain(): ToolDomain = ToolDomain(
        "diagnostics",
        "What the IDE's own analysis flags: the highlights of one file, the Problems view for the whole project, and its tabs",
        listOf(
            Tool(PROBLEMS) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::problemsOne)) },
            Tool(PROJECT_PROBLEMS, ::projectProblems),
            Tool(PROBLEMS_VIEW, ::problemsView),
        ),
    )

    private suspend fun problemsOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val severity = Severities.minimum(args)
        val max = args.int("max", DEFAULT_MAX)
        val (file, document) = readAction {
            val file = ReadTools.resolveFile(project, path)
            file to Locations.document(project, file)
        }
        val highlights = daemon.collect(file, document, severity)
            ?: throw ToolException("the IDE has not finished analysing $path; retry in a moment")
        return buildJsonObject {
            put("path", path)
            put("count", highlights.size)
            put("truncated", highlights.size > max)
            put(
                "problems",
                buildJsonArray {
                    highlights.take(max).forEach { h ->
                        add(
                            buildJsonObject {
                                put("line", h.line)
                                put("column", h.column)
                                put("severity", h.severity)
                                put("inspection", h.inspection ?: "")
                                put("message", h.message)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun projectProblems(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val group = args.optionalString("group")
        val (rows, total) = withContext(Dispatchers.EDT) {
            val collector = ProblemsCollector.getInstance(project)
            val all = collector.getProblemFiles().flatMap { collector.getFileProblems(it) } + collector.getOtherProblems()
            val matching = all.filter { group == null || it.group?.contains(group, ignoreCase = true) == true }
            matching.take(max).map(::row) to matching.size
        }
        if (reveal.mirroring) reveal.problems("")
        return ToolResult.toon(
            buildJsonObject {
                put("count", total)
                put("truncated", total > rows.size)
                put("problems", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private suspend fun problemsView(args: ToolArgs): ToolResult {
        val wanted = args.optionalString("tab")
        val tabs = withContext(Dispatchers.EDT) { tabs() }
        val chosen = wanted?.let { name -> tab(tabs, name) }
        chosen?.let { reveal.problems(it.id) }
        val selected = withContext(Dispatchers.EDT) { ProblemsViewToolWindowUtils.getSelectedTab(project)?.getTabId() ?: "" }
        return ToolResult.toon(
            buildJsonObject {
                put("selected", selected)
                put("count", tabs.size)
                put(
                    "tabs",
                    buildJsonArray {
                        tabs.forEach { tab ->
                            add(
                                buildJsonObject {
                                    put("id", tab.id)
                                    put("name", tab.name)
                                    put("selected", tab.id == selected)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private class ProblemsTab(val id: String, val name: String)

    private fun tabs(): List<ProblemsTab> {
        val toolWindow = ProblemsViewToolWindowUtils.getToolWindow(project) ?: throw ToolException("this IDE has no Problems tool window")
        return toolWindow.contentManager.contents.mapNotNull { content ->
            val tab = content.component as? ProblemsViewTab ?: return@mapNotNull null
            ProblemsTab(tab.getTabId(), plain(content.displayName ?: tab.getName(0)))
        }
    }

    private fun plain(title: String): String = StringUtil.removeHtmlTags(title).replace(WHITESPACE, " ").trim()

    private fun tab(tabs: List<ProblemsTab>, name: String): ProblemsTab =
        tabs.firstOrNull { it.id.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
            ?: throw ToolException("no Problems tab named $name; the tabs are ${tabs.joinToString { it.id }}")

    private fun row(problem: Problem): JsonObject = buildJsonObject {
        val inFile = problem as? FileProblem
        put("file", inFile?.let { Locations.relative(project, it.file) } ?: "")
        put("line", inFile?.line?.takeIf { it >= 0 }?.plus(1) ?: 0)
        put("column", inFile?.column?.takeIf { it >= 0 }?.plus(1) ?: 0)
        put("group", problem.group ?: "")
        put("message", problem.text)
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private val WHITESPACE = Regex("\\s+")

        val PROBLEMS = ToolSpec(
            "problems",
            "The errors and warnings the IDE's analysis shows for one file, with line, column, severity and the inspection " +
                "that raised each. Opens the file in an editor tab, since the IDE analyses open files.",
            listOf(
                Param("path", "File path, absolute or relative to the project root", required = false),
                Batch.paths("every touched file in one call"),
                Severities.PARAM,
                Param("max", "Maximum problems to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val PROJECT_PROBLEMS = ToolSpec(
            "project_problems",
            "Everything the Problems view lists right now across the project: file problems with their positions, " +
                "and problems with no file. Filter by group to isolate what one inspection family or plugin reports.",
            listOf(
                Param("max", "Maximum problems to return (default $DEFAULT_MAX)", type = "integer", required = false),
                Param("group", "Only problems whose group contains this text, e.g. an inspection family or a plugin", required = false),
            ),
        )

        val PROBLEMS_VIEW = ToolSpec(
            "problems_view",
            "Lists the tabs of the IDE's Problems tool window (Current File, Project Errors, and any a plugin adds, such as " +
                "Qodana's server-side analysis) and, given a tab, shows it to the user.",
            listOf(Param("tab", "Tab id or title to show (default: only list them)", required = false)),
        )
    }
}
