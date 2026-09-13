package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findOrCreateFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.writeText
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.diff.DiffPresenter
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path

internal class FileTools(private val project: Project, private val actions: IdeActions, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "files",
        "The project view's file menu on a path: copy its path, read or set its file type, add it to an ignore file, " +
            "delete it",
        listOf(Tool(COPY_PATH, ::copyPath), Tool(FILE_TYPE, ::fileType), Tool(IGNORE, ::ignore), Tool(DELETE_FILE, ::deleteFile)),
    )

    private suspend fun copyPath(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val kind = args.optionalString("kind") ?: "relative"
        val file = readAction { Locations.any(project, path) }
        val text = when (kind) {
            "absolute" -> file.path

            "relative" -> Locations.relative(project, file)

            "name" -> file.name

            "reference" -> {
                actions.dispatch(COPY_REFERENCE, TargetContext.target(args))
                ""
            }

            else -> throw ToolException("kind must be absolute, relative, name or reference")
        }
        if (text.isNotEmpty()) withContext(Dispatchers.EDT) { CopyPasteManager.getInstance().setContents(StringSelection(text)) }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("kind", kind)
                put("text", text)
                put("copied", true)
            },
        )
    }

    private suspend fun fileType(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val wanted = args.optionalString("type")
        val file = readAction { Locations.file(project, path) }
        if (wanted != null) {
            val manager = FileTypeManager.getInstance()
            val type = manager.findFileTypeByName(wanted) ?: throw ToolException("this IDE has no file type named $wanted")
            writeCommandAction(project, "Claude: associate ${file.name} with $wanted") {
                manager.associate(type, ExactFileNameMatcher(file.name))
            }
        }
        val type = readAction { file.fileType }
        return ToolResult.toon(
            buildJsonObject {
                put("path", path)
                put("type", type.name)
                put("display", type.displayName)
                put("binary", type.isBinary)
            },
        )
    }

    private suspend fun ignore(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val ignoreFile = args.optionalString("file") ?: GITIGNORE
        if (ignoreFile !in IGNORE_FILES) throw ToolException("file must be one of ${IGNORE_FILES.joinToString()}")
        val entry = Locations.relative(project, readAction { Locations.file(project, path) })
        val base = project.basePath ?: throw ToolException("this project has no directory on disk")
        val target = writeCommandAction(project, "Claude: ignore $path") {
            val file = VfsUtil.createDirectories(base).findOrCreateFile(ignoreFile)
            val lines = file.readText().lines().filter { it.isNotEmpty() }
            if (entry !in lines) file.writeText((lines + entry).joinToString("\n") + "\n")
            file
        }
        reveal.file(target)
        return ToolResult.toon(
            buildJsonObject {
                put("path", entry)
                put("file", ignoreFile)
                put("ignored", true)
            },
        )
    }

    private suspend fun deleteFile(args: ToolArgs): ToolResult {
        val paths = args.strings("paths")
        val files = readAction { inside(paths.map { Locations.any(project, it) }) }
        try {
            writeCommandAction(project, "Claude: delete ${paths.size} path(s)") { files.forEach { it.delete(this) } }
        } catch (e: IOException) {
            throw ToolException("cannot delete: ${e.message}", e)
        }
        LocalFileSystem.getInstance().refreshNioFiles(files.map { Path.of(it.path) })
        return ToolResult.toon(
            buildJsonObject {
                put("count", files.size)
                put("deleted", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private fun inside(files: List<VirtualFile>): List<VirtualFile> {
        val base = project.basePath
        val outside = files.firstOrNull { base == null || !DiffPresenter.isWithinRoot(it.path, base) }
        val problem = when {
            files.isEmpty() -> "paths must name at least one file or directory"
            outside != null -> "${outside.path} is outside the project"
            else -> return files
        }
        throw ToolException(problem)
    }

    companion object {

        private const val COPY_REFERENCE = "CopyReference"
        private const val GITIGNORE = ".gitignore"

        val IGNORE_FILES: List<String> = listOf(GITIGNORE, ".dockerignore", ".npmignore", ".eslintignore", ".prettierignore", ".helmignore")

        private val PATH = Param("path", "File or directory path, absolute or relative to the project root")

        val COPY_PATH = ToolSpec(
            "copy_path",
            "Copy Path on a file: absolute, relative (default) or name are returned and put on the clipboard; reference " +
                "runs the IDE's Copy Reference (the qualified name of the symbol at the position) onto the clipboard.",
            listOf(PATH, Param("kind", "absolute, relative (default), name or reference", required = false)),
        )

        val FILE_TYPE = ToolSpec(
            "file_type",
            "The file type the IDE assigns to a file; with type, associates the file's name with that type through the " +
                "IDE's file type manager, as Override File Type does.",
            listOf(PATH, Param("type", "A registered file type name to associate, e.g. PLAIN_TEXT, JSON, Kotlin", required = false)),
            mutates = true,
        )

        val IGNORE = ToolSpec(
            "ignore",
            "Adds a path to an ignore file at the project root (" + IGNORE_FILES.joinToString() + "), creating the file " +
                "when absent and skipping an entry already there; the ignore file opens in the editor.",
            listOf(PATH, Param("file", "The ignore file (default .gitignore)", required = false)),
            mutates = true,
        )

        val DELETE_FILE = ToolSpec(
            "delete_file",
            "Deletes files or directories of the project through the IDE's virtual file system, one undoable command, " +
                "all paths in one call; never outside the project. For a symbol or a file with usages use safe_delete.",
            listOf(Batch.param(Batch.PATHS, "The files or directories to delete, all in one call")),
            mutates = true,
        )
    }
}
