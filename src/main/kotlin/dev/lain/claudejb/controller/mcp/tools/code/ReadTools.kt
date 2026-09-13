package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal class ReadTools(private val project: Project, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "read",
        "Files as the IDE sees them, unsaved edits included",
        listOf(Tool(READ_FILE, ::readFile)),
    )

    private suspend fun readFile(args: ToolArgs): ToolResult = ToolResult.toon(Batch.run(args, Batch.PATHS, ::readOne))

    private suspend fun readOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val offset = args.int("offset", 1)
        val limit = args.int("limit", DEFAULT_LIMIT)
        if (offset < 1 || limit < 1) throw ToolException("offset and limit start at 1")
        val file = readAction { resolveFile(project, path) }
        val (row, from) = readAction {
            val text = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence?.toString()
                ?: String(file.contentsToByteArray(), file.charset)
            val lines = text.lines()
            val from = minOf(offset, lines.size + 1)
            val to = minOf(from + limit - 1, lines.size)
            buildJsonObject {
                put("path", path)
                put("lines", lines.size)
                put("from", from)
                put("to", to)
                put("text", lines.subList(from - 1, to).joinToString("\n"))
            } to from
        }
        if (reveal.mirroring) reveal.file(file, from, preview = true)
        return row
    }

    companion object {

        fun resolveFile(project: Project, path: String): VirtualFile {
            val file = locate(project, path)
            if (file.isDirectory) throw ToolException("$path is a directory")
            if (file.fileType.isBinary) throw ToolException("$path is binary")
            return file
        }

        fun resolveDirectory(project: Project, path: String): VirtualFile {
            val dir = locate(project, path)
            if (!dir.isDirectory) throw ToolException("$path is not a directory")
            return dir
        }

        private fun locate(project: Project, path: String): VirtualFile {
            val base = project.basePath ?: throw ToolException("this project has no directory on disk")
            val absolute = Path.of(path).let { if (it.isAbsolute) it else Path.of(base).resolve(it) }.normalize()
            return LocalFileSystem.getInstance().findFileByNioFile(absolute)
                ?: throw ToolException("no such path: $path")
        }

        private const val DEFAULT_LIMIT = 400

        val READ_FILE = ToolSpec(
            "read_file",
            "Reads a text file through the IDE, so unsaved editor changes are included, or several files at once with " +
                "paths. Use offset and limit for large files. The file is shown in the editor's preview tab, without focus.",
            listOf(
                Param("path", "File path, absolute or relative to the project root", required = false),
                Batch.paths("offset and limit apply to each"),
                Param("offset", "First line to return, 1-based (default 1)", type = "integer", required = false),
                Param(
                    "limit",
                    "Maximum number of lines to return (default $DEFAULT_LIMIT)",
                    type = "integer",
                    required = false,
                ),
            ),
        )
    }
}
