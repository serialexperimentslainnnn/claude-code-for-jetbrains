package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.packageDependencies.ForwardDependenciesBuilder
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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

internal class AnalyzeTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "analyze",
        "Code ▸ Inspect Code, Code Cleanup and Code ▸ Analyze on a scope: inspections in the Inspection Results window, " +
            "cleanup applied, the dependencies of files, and the data flow of an expression",
        listOf(
            Tool(INSPECT_SCOPE, ::inspectScope),
            Tool(CLEANUP, ::cleanup),
            Tool(FILE_DEPENDENCIES, ::dependencies),
            Tool(DATAFLOW, ::dataflow),
        ),
    )

    private suspend fun inspectScope(args: ToolArgs): ToolResult {
        val scope = scope(args)
        withContext(Dispatchers.EDT) {
            FocusKeeper.keeping(project) {
                (InspectionManager.getInstance(project).createNewGlobalContext() as GlobalInspectionContextBase).doInspections(scope)
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("scope", scope.displayName)
                put("started", true)
            },
        )
    }

    private suspend fun cleanup(args: ToolArgs): ToolResult {
        val scope = scope(args)
        withContext(Dispatchers.EDT) {
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
            val context = InspectionManager.getInstance(project).createNewGlobalContext() as GlobalInspectionContextBase
            FocusKeeper.keeping(project) { context.codeCleanup(scope, profile, "Claude: code cleanup", null, false) }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("scope", scope.displayName)
                put("started", true)
            },
        )
    }

    private suspend fun dependencies(args: ToolArgs): ToolResult {
        val direction = args.optionalString("direction") ?: "forward"
        val max = args.int("max", DEFAULT_MAX)
        if (direction == "backward") {
            actions.dispatch(BACKWARD_DEPENDENCIES, TargetContext.target(args))
            return ToolResult.toon(
                buildJsonObject {
                    put("direction", direction)
                    put("dispatched", true)
                },
            )
        }
        if (direction != "forward") throw ToolException("direction must be forward or backward")
        val scope = scope(args)
        val builder = ForwardDependenciesBuilder(project, scope, args.int("transitive", 0))
        withBackgroundProgress(project, "Claude: analysing dependencies", cancellable = true) { coroutineToIndicator { builder.analyze() } }
        val rows = readAction {
            builder.dependencies.entries.take(max).map { (file, uses) ->
                buildJsonObject {
                    put("file", name(file))
                    put("depends_on", buildJsonArray { uses.forEach { add(JsonPrimitive(name(it))) } })
                }
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("direction", direction)
                put("scope", scope.displayName)
                put("count", rows.size)
                put("files", buildJsonArray { rows.forEach { add(it) } })
            },
        )
    }

    private suspend fun dataflow(args: ToolArgs): ToolResult {
        val direction = args.optionalString("direction") ?: "to"
        val id = when (direction) {
            "to" -> SLICE_BACKWARD
            "from" -> SLICE_FORWARD
            else -> throw ToolException("direction must be to (what flows into this) or from (where this flows)")
        }
        val target = TargetContext.target(args)
        if (target.path == null) throw ToolException("dataflow needs path, line and column")
        actions.dispatch(id, target)
        return ToolResult.toon(
            buildJsonObject {
                put("direction", direction)
                put("path", target.path)
                put("line", target.line)
                put("column", target.column)
                put("dispatched", true)
            },
        )
    }

    private fun name(file: PsiFile): String = file.virtualFile?.let { Locations.relative(project, it) } ?: file.name

    private suspend fun scope(args: ToolArgs): AnalysisScope {
        val kind = args.optionalString("scope") ?: "project"
        val path = args.optionalString("path")
        val module = args.optionalString("module")
        return readAction {
            when (kind) {
                "project" -> AnalysisScope(project)
                "module" -> AnalysisScope(moduleNamed(needed(kind, "module", module)))
                "dir" -> AnalysisScope(directory(needed(kind, "path", path)))
                "file" -> AnalysisScope(Locations.psiFile(project, needed(kind, "path", path)))
                else -> throw ToolException("scope must be project, module, dir or file")
            }
        }
    }

    private fun needed(kind: String, key: String, value: String?): String = value ?: throw ToolException("scope=$kind needs $key")

    private fun moduleNamed(name: String) = ModuleManager.getInstance(project).findModuleByName(name)
        ?: throw ToolException("no module named $name; modules lists them")

    private fun directory(path: String) = PsiManager.getInstance(project).findDirectory(ReadTools.resolveDirectory(project, path))
        ?: throw ToolException("$path is not a directory of this project")

    companion object {

        private const val DEFAULT_MAX = 200
        private const val BACKWARD_DEPENDENCIES = "ShowBackwardPackageDeps"
        private const val SLICE_BACKWARD = "SliceBackward"
        private const val SLICE_FORWARD = "SliceForward"

        private val SCOPE_PARAMS = listOf(
            Param("scope", "project (default), module, dir or file", required = false),
            Param("path", "The directory or file, relative to the project root (scope=dir, file)", required = false),
            Param("module", "The module name (scope=module)", required = false),
        )

        val INSPECT_SCOPE = ToolSpec(
            "inspect_scope",
            "Runs Code ▸ Inspect Code with the current profile on the project, a module, a directory or a file: the IDE " +
                "analyses in the background and shows the Inspection Results window; problems and inspect give the " +
                "findings of a file as data.",
            SCOPE_PARAMS,
            mutates = true,
        )

        val CLEANUP = ToolSpec(
            "cleanup",
            "Runs Code ▸ Code Cleanup with the current profile on a scope: every cleanup inspection's fix is applied, as " +
                "one undoable command, and the changed files show in the editor.",
            SCOPE_PARAMS,
            mutates = true,
        )

        val FILE_DEPENDENCIES = ToolSpec(
            "file_dependencies",
            "Code ▸ Analyze ▸ Dependencies: for direction=forward (default) the files each file of the scope depends on, as " +
                "data, transitive levels deep when transitive is given; for direction=backward the IDE's Backward " +
                "Dependencies analysis is opened on path.",
            SCOPE_PARAMS + listOf(
                Param("direction", "forward (default) or backward", required = false),
                Param(
                    "transitive",
                    "Levels of transitive dependencies to follow (default 0: direct only)",
                    type = "integer",
                    required = false,
                ),
                Param("max", "Maximum files to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val DATAFLOW = ToolSpec(
            "dataflow",
            "Code ▸ Analyze ▸ Data Flow on the expression at a position: direction=to shows what flows into it, " +
                "direction=from where it flows; the IDE's Analyze Data Flow window opens without taking the focus.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("line", "1-based line of the expression", type = "integer"),
                Param("column", "1-based column of the expression (default 1)", type = "integer", required = false),
                Param("direction", "to (default) or from", required = false),
            ),
        )
    }
}
