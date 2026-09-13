package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.storage.WorkspaceEntity
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

internal class WorkspaceTools(private val project: Project) {

    fun domain(): ToolDomain = ToolDomain(
        "workspace",
        "The IDE's workspace model, read-only: the entities that describe the project (modules, content roots, source " +
            "roots, libraries, SDKs) as the model holds them",
        listOf(Tool(WORKSPACE, ::workspace)),
    )

    private suspend fun workspace(args: ToolArgs): ToolResult {
        val kind = args.optionalString("entity_type") ?: "module"
        val max = args.int("max", DEFAULT_MAX)
        val type = ENTITY_TYPES[kind] ?: throw ToolException("entity_type must be one of ${ENTITY_TYPES.keys.joinToString()}")
        val rows = readAction {
            WorkspaceModel.getInstance(project).currentSnapshot.entities(type).map(::row).toList()
        }
        return ToolResult.toon(
            buildJsonObject {
                put("entity_type", kind)
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("entities", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    private fun row(entity: WorkspaceEntity): JsonObject = buildJsonObject {
        when (entity) {
            is ModuleEntity -> {
                put("name", entity.name)
                put("type", entity.type?.name ?: "")
                put("dependencies", entity.dependencies.size)
                put("content_roots", entity.contentRoots.size)
            }

            is ContentRootEntity -> {
                put("module", entity.module.name)
                put("url", entity.url.url)
                put("source_roots", entity.sourceRoots.size)
                put("excluded", entity.excludedUrls.size)
            }

            is SourceRootEntity -> {
                put("module", entity.contentRoot.module.name)
                put("url", entity.url.url)
                put("root_type", entity.rootTypeId.name)
            }

            is LibraryEntity -> {
                put("name", entity.name)
                put("level", entity.tableId.level)
                put("roots", entity.roots.size)
            }

            is SdkEntity -> {
                put("name", entity.name)
                put("type", entity.type)
                put("version", entity.version ?: "")
                put("home", entity.homePath?.url ?: "")
            }

            else -> put("entity", entity.toString())
        }
        put("source", entity.entitySource.javaClass.simpleName)
    }

    companion object {

        private const val DEFAULT_MAX = 200

        val ENTITY_TYPES: Map<String, Class<out WorkspaceEntity>> = linkedMapOf(
            "module" to ModuleEntity::class.java,
            "content_root" to ContentRootEntity::class.java,
            "source_root" to SourceRootEntity::class.java,
            "library" to LibraryEntity::class.java,
            "sdk" to SdkEntity::class.java,
        )

        val WORKSPACE = ToolSpec(
            "workspace",
            "The entities of the IDE's workspace model by type: module (default), content_root, source_root, library or " +
                "sdk, each with its main attributes and the entity source that created it (the build system's import or " +
                "the user's edits).",
            listOf(
                Param("entity_type", "module (default), content_root, source_root, library or sdk", required = false),
                Param("max", "Maximum entities (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
