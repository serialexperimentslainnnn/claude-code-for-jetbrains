package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findOrCreateFile
import com.intellij.openapi.vfs.writeText
import com.intellij.psi.PsiDocumentManager
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.model.mcp.Batch
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.TextEdit
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.view.diff.DiffEditors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path

internal class EditTools(private val project: Project, private val reveal: Reveal) {

    fun domain(): ToolDomain = ToolDomain(
        "edit",
        "Text edits through the IDE's document model: one undo entry each, saved to disk, shown as a diff; every tool " +
            "takes one file or a list",
        listOf(
            Tool(REPLACE_TEXT) { ToolResult.toon(Batch.run(it, Batch.REPLACEMENTS, ::replaceOne)) },
            Tool(INSERT_TEXT) { ToolResult.toon(Batch.run(it, Batch.INSERTIONS, ::insertOne)) },
            Tool(CREATE_FILE) { ToolResult.toon(Batch.run(it, Batch.FILES, ::createOne)) },
            Tool(WRITE_FILE) { ToolResult.toon(Batch.run(it, Batch.FILES, ::writeOne)) },
        ),
    )

    private suspend fun replaceOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val old = args.string("old_string")
        val new = args.string("new_string")
        val all = args.boolean("replace_all", false)
        val outcome = edit(path, "replace text in") { TextEdit.replace(it, old, new, all) }
        return buildJsonObject {
            put("path", path)
            put("replaced", outcome.count)
            put("lines", buildJsonArray { outcome.lines.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private suspend fun insertOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val line = args.int("line", 0)
        val content = args.string("content")
        val outcome = edit(path, "insert text in") { TextEdit.insertAt(it, line, content) }
        return buildJsonObject {
            put("path", path)
            put("line", line)
            put("lines_inserted", outcome.count)
        }
    }

    private suspend fun createOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val content = args.string("content")
        val absolute = Locations.inside(project, path)
        if (exists(absolute)) throw ToolException("$path already exists; use write_file, replace_text or insert_text to change it")
        create(path, absolute, content)
        return buildJsonObject {
            put("path", path)
            put("lines", content.lines().size)
        }
    }

    private suspend fun writeOne(args: ToolArgs): JsonObject {
        val path = args.string("path")
        val content = args.string("content")
        val absolute = Locations.inside(project, path)
        val existed = exists(absolute)
        if (existed) edit(path, "write") { TextEdit.whole(it, content) } else create(path, absolute, content)
        return buildJsonObject {
            put("path", path)
            put("created", !existed)
            put("lines", content.lines().size)
        }
    }

    private fun exists(absolute: Path): Boolean = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolute) != null

    private suspend fun create(path: String, absolute: Path, content: String) {
        if (absolute.parent == null) throw ToolException("$path has no parent directory")
        val file = try {
            writeCommandAction(project, "Claude: create ${absolute.fileName}") { createOnDisk(absolute, content) }
        } catch (e: IOException) {
            throw ToolException("cannot create $path: ${e.message}", e)
        }
        reveal.file(file)
    }

    private fun createOnDisk(absolute: Path, content: String): VirtualFile =
        VfsUtil.createDirectories(absolute.parent.toString()).findOrCreateFile(absolute.fileName.toString()).also { it.writeText(content) }

    private suspend fun edit(path: String, verb: String, change: (String) -> TextEdit.Outcome): TextEdit.Outcome {
        Locations.inside(project, path)
        val (file, document) = readAction {
            val file = ReadTools.resolveFile(project, path)
            file to Locations.document(project, file)
        }
        val label = "Claude: $verb ${file.name}"
        val (before, outcome) = writeCommandAction(project, label) {
            val before = document.immutableCharSequence.toString()
            val outcome = change(before)
            apply(file, document, outcome.text)
            before to outcome
        }
        LocalHistory.getInstance().putSystemLabel(project, label)
        withContext(Dispatchers.EDT) {
            DiffEditors.openTextDiff(
                project,
                file.path,
                DiffEditors.TextSide("Before", before),
                DiffEditors.TextSide("After Claude's edit", outcome.text),
            )
        }
        return outcome
    }

    private fun apply(file: VirtualFile, document: Document, text: String) {
        if (!document.isWritable) throw ToolException("${Locations.relative(project, file)} is read-only")
        document.replaceString(0, document.textLength, text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        FileDocumentManager.getInstance().saveDocument(document)
    }

    companion object {

        private const val PATH = "File path, absolute or relative to the project root"

        val REPLACE_TEXT = ToolSpec(
            "replace_text",
            "Replaces one literal occurrence of old_string in a file (every occurrence with replace_all), as one undo entry, " +
                "saved to disk and shown as a Before/After diff. Fails when old_string is missing or ambiguous. Several files " +
                "at once with edits.",
            listOf(
                Param("path", PATH, required = false),
                Param("old_string", "Exact text to replace; must be unique unless replace_all is true", required = false),
                Param("new_string", "Text that replaces it", required = false),
                Param("replace_all", "true to replace every occurrence (default false)", type = "boolean", required = false),
                Batch.param(Batch.REPLACEMENTS, "Several replacements at once: a list of {path, old_string, new_string, replace_all?}"),
            ),
            mutates = true,
        )

        val INSERT_TEXT = ToolSpec(
            "insert_text",
            "Inserts whole lines before a 1-based line of a file (lines + 1 appends), as one undo entry, saved to disk " +
                "and shown as a Before/After diff. Several files at once with edits.",
            listOf(
                Param("path", PATH, required = false),
                Param("line", "1-based line the content goes before; one past the last line appends", type = "integer", required = false),
                Param("content", "Text to insert; a trailing newline is added when missing", required = false),
                Batch.param(Batch.INSERTIONS, "Several insertions at once: a list of {path, line, content}"),
            ),
            mutates = true,
        )

        val CREATE_FILE = ToolSpec(
            "create_file",
            "Creates a new file with the given content through the IDE, creating missing directories, and opens it. " +
                "Fails when the file already exists. Several files at once with files.",
            listOf(
                Param("path", "Path of the new file, absolute or relative to the project root", required = false),
                Param("content", "Full content of the new file", required = false),
                Batch.param(Batch.FILES, "Several new files at once: a list of {path, content}"),
            ),
            mutates = true,
        )

        val WRITE_FILE = ToolSpec(
            "write_file",
            "Writes a whole file: replaces the content of an existing file as one undo entry shown as a Before/After " +
                "diff, or creates it when absent. Use it for full rewrites; replace_text for targeted changes. Several " +
                "files at once with files.",
            listOf(
                Param("path", PATH, required = false),
                Param("content", "Full content the file ends up with", required = false),
                Batch.param(Batch.FILES, "Several files at once: a list of {path, content}"),
            ),
            mutates = true,
        )
    }
}
