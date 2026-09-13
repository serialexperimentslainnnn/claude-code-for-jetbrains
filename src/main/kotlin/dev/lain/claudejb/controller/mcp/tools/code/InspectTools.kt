package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiFile
import dev.lain.claudejb.model.mcp.Batch
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

internal class InspectTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "inspect",
        "The IDE's inspections on demand, without waiting for the editor: list them, or run them on one file",
        listOf(Tool(INSPECTIONS, ::inspections), Tool(INSPECT) { ToolResult.toon(Batch.run(it, Batch.PATHS, ::inspectOne)) }),
    )

    private suspend fun inspections(args: ToolArgs): ToolResult {
        val query = args.optionalString("query").orEmpty()
        val max = args.int("max", DEFAULT_MAX)
        val rows = readAction {
            val profile = InspectionProfileManager.getInstance(project).currentProfile
            profile.getInspectionTools(null)
                .filter { query.isEmpty() || it.shortName.contains(query, true) || it.displayName.contains(query, true) }
                .sortedBy { it.shortName }
                .take(max)
                .map { tool ->
                    buildJsonObject {
                        put("id", tool.shortName)
                        put("name", tool.displayName)
                        put("group", tool.groupDisplayName)
                        put("enabled", profile.isToolEnabled(HighlightDisplayKey.find(tool.shortName), null))
                    }
                }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("truncated", rows.size >= max)
                put("inspections", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private suspend fun inspectOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val only = args.optionalString("inspection")
        val minimum = Severities.minimum(args)
        val max = args.int("max", DEFAULT_MAX)
        val psiFile = readAction { Locations.psiFile(project, path) }
        val tools = readAction { applicable(psiFile, only, minimum) }
        if (tools.isEmpty()) throw ToolException(missing(only, path))
        val context = InspectionManager.getInstance(project).createNewGlobalContext()
        val rows = ArrayList<JsonObject>()
        try {
            for (applicable in tools) {
                if (rows.size >= max) break
                val problems = smartReadAction(project) { run(psiFile, applicable.tool, context) }
                rows += readAction { problems.take(max - rows.size).map { row(applicable, it) } }
            }
        } finally {
            context.cleanup()
        }
        return buildJsonObject {
            put("path", path)
            put("inspections", tools.size)
            put("truncated", rows.size >= max)
            put("problems", buildJsonArray { rows.forEach { add(it) } })
        }
    }

    private fun missing(only: String?, path: String): String =
        if (only == null) "no inspection applies to " + path else "no enabled inspection named " + only + " applies to " + path

    private class Applicable(val tool: InspectionToolWrapper<*, *>, val severity: String)

    private fun applicable(psiFile: PsiFile, only: String?, minimum: HighlightSeverity?): List<Applicable> {
        val profile = InspectionProfileManager.getInstance(project).currentProfile
        return profile.getInspectionTools(psiFile)
            .filter { it.isApplicable(psiFile.language) }
            .mapNotNull { tool ->
                val key = HighlightDisplayKey.find(tool.shortName) ?: return@mapNotNull null
                val severity = profile.getErrorLevel(key, psiFile).severity
                val wanted = when {
                    only != null -> tool.shortName.equals(only, true)
                    !profile.isToolEnabled(key, psiFile) -> false
                    else -> minimum == null || severity >= minimum
                }
                if (wanted) Applicable(tool, severity.name) else null
            }
    }

    private fun run(psiFile: PsiFile, tool: InspectionToolWrapper<*, *>, context: GlobalInspectionContext): List<ProblemDescriptor> =
        try {
            InspectionEngine.runInspectionOnFile(psiFile, tool, context)
        } catch (e: IndexNotReadyException) {
            throw ToolException("the IDE is still indexing; retry in a moment", e)
        }

    private fun row(applicable: Applicable, problem: ProblemDescriptor): JsonObject = buildJsonObject {
        put("line", problem.lineNumber + 1)
        put("inspection", applicable.tool.shortName)
        put("severity", applicable.severity)
        put("message", ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.psiElement))
    }

    companion object {

        private const val DEFAULT_MAX = 100

        val INSPECTIONS = ToolSpec(
            "inspections",
            "Lists the inspections of the current profile — id, name, group and whether it is enabled — optionally " +
                "filtered by a query.",
            listOf(
                Param("query", "Part of an inspection id or name, case-insensitive (default: all)", required = false),
                Param("max", "Maximum inspections to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val INSPECT = ToolSpec(
            "inspect",
            "Runs the profile's enabled inspections on one file, or a single inspection by id, and returns each finding " +
                "with its line, severity and message; the profile's information-level hints stay out unless severity=all.",
            listOf(
                Param("path", "File path, absolute or relative to the project root", required = false),
                Batch.paths("one result each"),
                Param("inspection", "Run only this inspection id (default: every enabled inspection)", required = false),
                Severities.PARAM,
                Param("max", "Maximum findings to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
