package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessorBase
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path

internal class RefactorTools(private val project: Project, private val refactorings: Refactorings = Refactorings(project)) {

    fun domain(): ToolDomain = ToolDomain(
        "refactor",
        "Rename, move and safe-delete with every reference updated, as the IDE's Refactor menu does",
        listOf(Tool(RENAME, ::rename), Tool(MOVE_FILE, ::moveFile), Tool(SAFE_DELETE, ::safeDelete)),
    )

    private suspend fun rename(args: ToolArgs): ToolResult {
        val newName = args.string("new_name")
        val max = args.int("max", DEFAULT_MAX)
        val (element, from, refactoring) = readAction {
            val element = refactorings.target(args)
            val from = refactorings.name(element)
            validName(element, from, newName)
            val refactoring = RefactoringFactory.getInstance(project).createRename(element, newName)
            refactoring.setInteractive(null)
            refactoring.setPreviewUsages(false)
            Triple(element, from, refactoring)
        }
        val usages = refactorings.usages(refactoring)
        readAction { conflicts(element, newName, usages) }
        val changes = refactorings.rows(usages.toList(), max)
        refactorings.perform(refactoring, usages)
        return ToolResult.toon(
            buildJsonObject {
                put("from", from)
                put("to", newName)
                put("usages", usages.size)
                put("files", changes.files)
                put("truncated", changes.truncated)
                put("changes", buildJsonArray { changes.rows.forEach { add(it) } })
            },
        )
    }

    private fun validName(element: PsiElement, from: String, newName: String) {
        if (from == newName) throw ToolException("it is already named $newName")
        if (!RenameUtil.isValidName(project, element, newName)) {
            throw ToolException("$newName is not a valid name for this ${Locations.kind(element)}")
        }
    }

    private fun conflicts(element: PsiElement, newName: String, usages: Array<UsageInfo>) {
        val conflicts = MultiMap<PsiElement, String>()
        RenameUtil.addConflictDescriptions(usages, conflicts)
        val processor = RenamePsiElementProcessorBase.forPsiElement(element)
        processor.findExistingNameConflicts(element, newName, conflicts, mapOf(element to newName))
        if (!conflicts.isEmpty) throw ToolException("renaming would conflict: " + conflicts.values().joinToString("; "))
    }

    private suspend fun moveFile(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val destination = args.string("destination")
        val target = Locations.inside(project, destination)
        val (file, psiFile) = readAction { source(path, target, destination) }
        val directory = edtWriteAction { createDirectories(target) }
        val psiDirectory = readAction { destinationOf(directory, file.name, destination) }
        val processor = MoveFilesOrDirectoriesProcessor(project, arrayOf<PsiElement>(psiFile), psiDirectory, false, true, null, null)
        processor.setPreviewUsages(false)
        withContext(Dispatchers.EDT) { writeIntentReadAction { processor.run() } }
        refactorings.saveAll()
        return ToolResult.toon(
            buildJsonObject {
                put("from", path)
                put("to", Locations.relative(project, directory) + "/" + file.name)
            },
        )
    }

    private fun source(path: String, target: Path, destination: String): Pair<VirtualFile, PsiFile> {
        val file = Locations.file(project, path)
        if (file.parent.toNioPath() == target) throw ToolException("$path is already in $destination")
        return file to (PsiManager.getInstance(project).findFile(file) ?: throw ToolException("the IDE has no PSI for $path"))
    }

    private fun createDirectories(target: Path): VirtualFile = try {
        VfsUtil.createDirectories(target.toString())
    } catch (e: IOException) {
        throw ToolException("cannot create $target: ${e.message}", e)
    }

    private fun destinationOf(directory: VirtualFile, name: String, destination: String): PsiDirectory {
        if (directory.findChild(name) != null) throw ToolException("$destination already contains $name")
        return PsiManager.getInstance(project).findDirectory(directory) ?: throw ToolException("the IDE has no PSI for $destination")
    }

    private suspend fun safeDelete(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val (target, refactoring) = readAction {
            val element = refactorings.target(args)
            if (!SafeDeleteProcessor.validElement(element)) {
                throw ToolException("the IDE cannot safe-delete this ${Locations.kind(element)}")
            }
            val refactoring = RefactoringFactory.getInstance(project).createSafeDelete(arrayOf(element))
            refactoring.isSearchInComments = false
            refactoring.isSearchInNonJavaFiles = false
            refactoring.setInteractive(null)
            refactoring.setPreviewUsages(false)
            refactorings.name(element) to refactoring
        }
        val usages = refactorings.usages(refactoring)
        val unsafe = usages.filter { it is SafeDeleteReferenceUsageInfo && !it.isSafeDelete }
        val blocking = refactorings.rows(unsafe, max)
        if (unsafe.isEmpty()) refactorings.perform(refactoring, usages)
        return ToolResult.toon(
            buildJsonObject {
                put("deleted", unsafe.isEmpty())
                put("target", target)
                put("unsafe", unsafe.size)
                put("truncated", blocking.truncated)
                put("usages", buildJsonArray { blocking.rows.forEach { add(it) } })
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 50
        private const val PATH = "File path, absolute or relative to the project root"
        private val SYMBOL = listOf(
            Param("line", "1-based line of the symbol; omit to target the file itself", type = "integer", required = false),
            Param("column", "1-based column of the symbol (default 1)", type = "integer", required = false),
        )

        val RENAME = ToolSpec(
            "rename",
            "Renames the symbol at a position, or the file when no line is given, updating every reference as the IDE's " +
                "Rename does. Fails, changing nothing, when the new name is invalid or would conflict.",
            listOf(Param("path", PATH), Param("new_name", "The new name")) + SYMBOL +
                Param("max", "Maximum changed usages to list (default $DEFAULT_MAX)", type = "integer", required = false),
            mutates = true,
        )

        val MOVE_FILE = ToolSpec(
            "move_file",
            "Moves a file into a directory (created when missing) with the IDE's Move refactoring, so packages, imports " +
                "and references follow. Fails when the destination already holds a file of that name.",
            listOf(Param("path", PATH), Param("destination", "Directory path, absolute or relative to the project root")),
            mutates = true,
        )

        val SAFE_DELETE = ToolSpec(
            "safe_delete",
            "Deletes the symbol at a position, or the file when no line is given, only when nothing else uses it; " +
                "otherwise deletes nothing and lists the usages that block it.",
            listOf(Param("path", PATH)) + SYMBOL +
                Param("max", "Maximum blocking usages to list (default $DEFAULT_MAX)", type = "integer", required = false),
            mutates = true,
        )
    }
}
