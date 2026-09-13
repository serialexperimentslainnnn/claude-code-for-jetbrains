package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExportableOrderEntry
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleSourceOrderEntry
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vcs.ProjectLevelVcsManager
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

internal class ProjectTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "project",
        "The project as Project Structure shows it: name, SDK, indexing and VCS; its modules; a module's dependencies; " +
            "and adding an existing library to a module",
        listOf(
            Tool(PROJECT, ::overview),
            Tool(MODULES, ::modules),
            Tool(DEPENDENCIES, ::dependencies),
            Tool(DEPENDENCY_ADD, ::dependencyAdd),
        ),
    )

    private suspend fun overview(ignored: ToolArgs): ToolResult {
        val (sdk, modulesCount) = readAction {
            (ProjectRootManager.getInstance(project).projectSdk?.name ?: "") to ModuleManager.getInstance(project).modules.size
        }
        val vcs = ProjectLevelVcsManager.getInstance(project).getAllActiveVcss().map { it.name }
        return ToolResult.toon(
            buildJsonObject {
                put("name", project.name)
                put("base_path", project.basePath ?: "")
                put("sdk", sdk)
                put("indexing", DumbService.getInstance(project).isDumb)
                put("modules_count", modulesCount)
                put("vcs", buildJsonArray { vcs.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private suspend fun modules(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val (rows, total) = readAction {
            val all = ModuleManager.getInstance(project).modules
            all.take(max).map(::moduleRow) to all.size
        }
        return list("modules", rows, total)
    }

    private fun moduleRow(module: Module): JsonObject = buildJsonObject {
        val roots = ModuleRootManager.getInstance(module)
        put("name", module.name)
        put("type", ModuleType.get(module).id)
        put("content_roots", roots.contentRoots.size)
        put("sdk", roots.sdk?.name ?: "")
    }

    private suspend fun dependencies(args: ToolArgs): ToolResult {
        val name = args.string("module")
        val max = args.int("max", DEFAULT_MAX)
        val (rows, total) = readAction {
            val entries = ModuleRootManager.getInstance(module(name)).orderEntries
            entries.take(max).map(::dependencyRow) to entries.size
        }
        return list("dependencies", rows, total)
    }

    private fun dependencyRow(entry: OrderEntry): JsonObject {
        val (kind, name) = when (entry) {
            is LibraryOrderEntry -> "Library" to (entry.libraryName ?: entry.presentableName)
            is ModuleOrderEntry -> "Module" to entry.moduleName
            is JdkOrderEntry -> "Sdk" to (entry.jdkName ?: entry.presentableName)
            is ModuleSourceOrderEntry -> "Source" to entry.presentableName
            else -> "Other" to entry.presentableName
        }
        val exportable = entry as? ExportableOrderEntry
        return buildJsonObject {
            put("name", name)
            put("kind", kind)
            put("scope", exportable?.scope?.name?.lowercase() ?: "")
            put("exported", exportable?.isExported ?: false)
        }
    }

    private suspend fun dependencyAdd(args: ToolArgs): ToolResult {
        val moduleName = args.string("module")
        val libraryName = args.string("library")
        val scopeName = args.optionalString("scope") ?: "compile"
        val scope = SCOPES[scopeName.lowercase()] ?: throw ToolException("scope must be ${SCOPES.keys.joinToString()}, not $scopeName")
        val (module, library) = readAction { module(moduleName) to library(libraryName) }
        val present = readAction { dependsOn(module, library) }
        if (!present) withContext(Dispatchers.Default) { ModuleRootModificationUtil.addDependency(module, library, scope, false) }
        return ToolResult.toon(
            buildJsonObject {
                put("module", moduleName)
                put("library", libraryName)
                put("scope", scopeName.lowercase())
                put("added", !present)
            },
        )
    }

    private fun dependsOn(module: Module, library: Library): Boolean =
        ModuleRootManager.getInstance(module).orderEntries.any { it is LibraryOrderEntry && it.library == library }

    private fun module(name: String): Module =
        ModuleManager.getInstance(project).findModuleByName(name) ?: throw ToolException("no module named $name; modules lists them")

    private fun library(name: String): Library {
        val registrar = LibraryTablesRegistrar.getInstance()
        return registrar.getLibraryTable(project).getLibraryByName(name)
            ?: registrar.libraryTable.getLibraryByName(name)
            ?: throw ToolException("no project or global library named $name; create it in Project Structure > Libraries first")
    }

    private fun list(key: String, rows: List<JsonObject>, total: Int): ToolResult = ToolResult.toon(
        buildJsonObject {
            put("count", total)
            put("truncated", total > rows.size)
            put(key, buildJsonArray { rows.forEach { add(it) } })
        },
    )

    companion object {

        private const val DEFAULT_MAX = 100

        val SCOPES: Map<String, DependencyScope> = linkedMapOf(
            "compile" to DependencyScope.COMPILE,
            "test" to DependencyScope.TEST,
            "runtime" to DependencyScope.RUNTIME,
            "provided" to DependencyScope.PROVIDED,
        )

        val PROJECT = ToolSpec(
            "project",
            "The open project at a glance: name, base path, project SDK, whether the IDE is still indexing, how many modules " +
                "it has and which VCSs are active. Call it first to know what kind of project you are in.",
        )

        val MODULES = ToolSpec(
            "modules",
            "Lists the project's modules as Project Structure shows them: name, module type id, number of content roots and " +
                "the module SDK (empty when inherited from the project).",
            listOf(Param("max", "Maximum modules to return (default $DEFAULT_MAX)", type = "integer", required = false)),
        )

        val DEPENDENCIES = ToolSpec(
            "dependencies",
            "The order entries of one module in classpath order: libraries, module dependencies, the SDK and the module's " +
                "own sources, each with its scope (compile, test, runtime, provided) and whether it is exported.",
            listOf(
                Param("module", "Module name as modules lists it"),
                Param("max", "Maximum entries to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val DEPENDENCY_ADD = ToolSpec(
            "dependency_add",
            "Adds an existing project or global library to a module's dependencies through the IDE's project model, exactly " +
                "as Project Structure does; added is false when the module already depends on it. It does not create " +
                "libraries or edit build files: for Gradle or Maven projects change the build file and reload instead.",
            listOf(
                Param("module", "Module name as modules lists it"),
                Param("library", "Name of an existing project or global library, as Project Structure > Libraries shows it"),
                Param("scope", "compile (default), test, runtime or provided", required = false),
            ),
            mutates = true,
        )
    }
}
