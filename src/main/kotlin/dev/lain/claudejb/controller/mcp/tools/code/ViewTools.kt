package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.nio.file.Path

internal class ViewTools(private val project: Project, private val actions: IdeActions) {

    fun domain(): ToolDomain = ToolDomain(
        "views",
        "The project view's file menu beyond editing: the IDE's diff of two files, a file against the editor, marking a " +
            "directory as a source, test, resource or excluded root, and opening a path outside the IDE",
        listOf(Tool(DIFF_SHOW, ::diffShow), Tool(COMPARE, ::compare), Tool(MARK_AS, ::markAs), Tool(OPEN_IN, ::openIn)),
    )

    private suspend fun diffShow(args: ToolArgs): ToolResult {
        val left = args.string("left")
        val right = args.string("right")
        val title = args.optionalString("title") ?: "$left vs $right"
        val (a, b) = readAction { ReadTools.resolveFile(project, left) to ReadTools.resolveFile(project, right) }
        FocusKeeper.keep(project) {
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(title, factory.create(project, a), factory.create(project, b), left, right)
            DiffManager.getInstance().showDiff(project, request)
        }
        return ToolResult.toon(
            buildJsonObject {
                put("left", left)
                put("right", right)
                put("shown", true)
            },
        )
    }

    private suspend fun compare(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val other = args.optionalString("other")
        if (other != null) {
            return diffShow(
                ToolArgs(
                    buildJsonObject {
                        put("left", path)
                        put("right", other)
                    },
                    args.toolUseId,
                ),
            )
        }
        actions.dispatch(COMPARE_WITH_EDITOR, TargetContext.target(args))
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("other", "the active editor")
                put("dispatched", true)
            },
        )
    }

    private suspend fun markAs(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val kind = args.string("kind")
        if (kind !in ROOT_KINDS) throw ToolException("kind must be one of ${ROOT_KINDS.joinToString()}")
        val dir = readAction { ReadTools.resolveDirectory(project, path) }
        val module = readAction { ProjectFileIndex.getInstance(project).getModuleForFile(dir, false) }
            ?: throw ToolException("$path belongs to no module; mark roots inside a module's content")
        ModuleRootModificationUtil.updateModel(module) { model -> mark(model, dir, kind) }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("kind", kind)
                put("module", module.name)
            },
        )
    }

    private fun mark(model: ModifiableRootModel, dir: VirtualFile, kind: String) {
        val entry = model.contentEntries.firstOrNull { entry -> entry.file?.let { VfsUtilCore.isAncestor(it, dir, false) } == true }
            ?: throw ToolException("${dir.path} is outside every content root of the module")
        entry.sourceFolders.filter { it.file == dir }.forEach(entry::removeSourceFolder)
        entry.excludeFolders.filter { it.file == dir }.forEach(entry::removeExcludeFolder)
        when (kind) {
            "excluded" -> entry.addExcludeFolder(dir)
            "unmark" -> Unit
            else -> entry.addSourceFolder(dir, rootType(kind))
        }
    }

    private fun rootType(kind: String): JpsModuleSourceRootType<*> = when (kind) {
        "source" -> JavaSourceRootType.SOURCE
        "test" -> JavaSourceRootType.TEST_SOURCE
        "resources" -> JavaResourceRootType.RESOURCE
        else -> JavaResourceRootType.TEST_RESOURCE
    }

    private suspend fun openIn(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val where = args.optionalString("where") ?: "file_manager"
        val file = readAction { Locations.any(project, path) }
        when (where) {
            "file_manager" -> reveal(file)
            "terminal" -> actions.dispatch(OPEN_IN_TERMINAL, TargetContext.target(args))
            "app" -> actions.dispatch(OPEN_IN_APP, TargetContext.target(args))
            else -> throw ToolException("where must be file_manager, terminal or app")
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("where", where)
                put("opened", true)
            },
        )
    }

    private fun reveal(file: VirtualFile) {
        val path = Path.of(file.path)
        if (file.isDirectory) RevealFileAction.openDirectory(path) else RevealFileAction.openFile(path)
    }

    companion object {

        private const val COMPARE_WITH_EDITOR = "CompareFileWithEditor"
        private const val OPEN_IN_TERMINAL = "Terminal.OpenInTerminal"
        private const val OPEN_IN_APP = "OpenInAssociatedApplication"

        val ROOT_KINDS: List<String> = listOf("source", "test", "resources", "test_resources", "excluded", "unmark")

        val DIFF_SHOW = ToolSpec(
            "diff_show",
            "Shows the IDE's diff of two files of the project, side by side, without taking the focus.",
            listOf(
                Param("left", "Left file, relative to the project root"),
                Param("right", "Right file, relative to the project root"),
                Param("title", "Title of the diff window (default: the two paths)", required = false),
            ),
        )

        val COMPARE = ToolSpec(
            "compare",
            "Compares a file with another (other) in the IDE's diff, or with the file in the active editor when other is " +
                "not given, as the project view's Compare With and Compare File with Editor do.",
            listOf(
                Param("path", "File path, absolute or relative to the project root"),
                Param("other", "The other file (default: the active editor's file)", required = false),
            ),
        )

        val MARK_AS = ToolSpec(
            "mark_as",
            "Mark Directory as: source, test, resources, test_resources or excluded root of its module, or unmark it, " +
                "through the project model, as the project view's menu does.",
            listOf(
                Param("path", "Directory, relative to the project root"),
                Param("kind", "source, test, resources, test_resources, excluded or unmark"),
            ),
            mutates = true,
        )

        val OPEN_IN = ToolSpec(
            "open_in",
            "Opens a path outside the editor: where=file_manager reveals it in the system's file manager, terminal opens " +
                "the IDE's Terminal there, app opens it in its associated application.",
            listOf(
                Param("path", "File or directory, relative to the project root"),
                Param("where", "file_manager (default), terminal or app", required = false),
            ),
        )
    }
}
