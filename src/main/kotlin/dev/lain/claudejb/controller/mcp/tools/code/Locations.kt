package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.util.DocumentUtil
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal class Located(val psiFile: PsiFile, val document: Document, val offset: Int)

internal object Locations {

    val POSITION = listOf(
        Param("path", "File path, absolute or relative to the project root"),
        Param("line", "1-based line", type = "integer"),
        Param("column", "1-based column (default 1)", type = "integer", required = false),
    )

    val OPTIONAL_POSITION = POSITION.map { it.copy(required = false) }

    fun relative(project: Project, file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        return file.path.removePrefix("$base/")
    }

    fun kind(value: Any): String = value.javaClass.simpleName.removePrefix("Psi").removePrefix("Kt").removeSuffix("Impl")

    fun absolute(project: Project, path: String): Path {
        val base = project.basePath ?: throw ToolException("this project has no directory on disk")
        return Path.of(path).let { if (it.isAbsolute) it else Path.of(base).resolve(it) }.normalize()
    }

    fun inside(project: Project, path: String): Path {
        val absolute = absolute(project, path)
        if (!DiffPresenter.isWithinRoot(absolute.toString(), project.basePath)) throw ToolException("$path is outside the project")
        return absolute
    }

    fun file(project: Project, path: String): VirtualFile {
        val file = any(project, path)
        if (file.isDirectory) throw ToolException(path + " is a directory")
        return file
    }

    fun any(project: Project, path: String): VirtualFile =
        LocalFileSystem.getInstance().findFileByNioFile(absolute(project, path)) ?: throw ToolException("no such path: " + path)

    fun lineText(document: Document, line: Int): String =
        document.immutableCharSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()

    fun psiFile(project: Project, path: String): PsiFile {
        val file = ReadTools.resolveFile(project, path)
        return PsiDocumentManager.getInstance(project).getPsiFile(document(project, file))
            ?: throw ToolException("$path has no PSI: the IDE does not parse this file type")
    }

    fun document(project: Project, file: VirtualFile): Document =
        FileDocumentManager.getInstance().getDocument(file) ?: throw ToolException("${relative(project, file)} is binary")

    fun locate(project: Project, args: ToolArgs): Located {
        val psiFile = psiFile(project, args.string("path"))
        val document = psiFile.viewProvider.document ?: throw ToolException("${args.string("path")} has no document")
        return Located(psiFile, document, offset(document, args.int("line", 0) - 1, args.int("column", 1) - 1))
    }

    private fun offset(document: Document, line: Int, column: Int): Int {
        if (!DocumentUtil.isValidLine(line, document)) {
            throw ToolException("line ${line + 1} is outside the file (${document.lineCount} lines)")
        }
        val offset = document.getLineStartOffset(line) + column.coerceAtLeast(0)
        if (offset > document.getLineEndOffset(line)) throw ToolException("column ${column + 1} is past the end of line ${line + 1}")
        return offset
    }

    fun declarationAt(project: Project, args: ToolArgs): PsiElement {
        val at = locate(project, args)
        val reference = at.psiFile.findReferenceAt(at.offset)
        val found = if (reference != null) reference.resolve() else named(at.psiFile.findElementAt(at.offset))
        return found ?: throw ToolException("no symbol resolves at line ${args.int("line", 0)}, column ${args.int("column", 1)}")
    }

    private fun named(leaf: PsiElement?): PsiElement? =
        leaf?.let { generateSequence(it) { element -> element.parent }.firstOrNull { element -> element is PsiNamedElement } }

    fun describe(project: Project, element: PsiElement): JsonObject = buildJsonObject { place(project, element) }

    fun JsonObjectBuilder.place(project: Project, element: PsiElement, withText: Boolean = true) {
        val spot = spot(project, element)
        put("file", spot?.file ?: "")
        put("line", spot?.line ?: 0)
        put("column", spot?.column ?: 0)
        if (withText) put("text", spot?.text ?: "")
    }

    fun located(element: PsiElement): Boolean = element.navigationElement.containingFile?.virtualFile != null

    private class Spot(val file: String, val line: Int, val column: Int, val text: String)

    private fun spot(project: Project, element: PsiElement): Spot? {
        val target = element.navigationElement
        val virtual = target.containingFile?.virtualFile ?: return null
        val file = relative(project, virtual)
        val document = FileDocumentManager.getInstance().getDocument(virtual) ?: return Spot(file, 0, 0, "")
        val offset = target.textOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        return Spot(file, line + 1, offset - document.getLineStartOffset(line) + 1, lineText(document, line))
    }
}
