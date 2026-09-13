package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.Refactoring
import com.intellij.usageView.UsageInfo
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class UsageRows(val rows: List<JsonObject>, val files: Int, val truncated: Boolean)

internal class Refactorings(private val project: Project) {

    fun target(args: ToolArgs): PsiElement =
        if (args.optionalString("line") == null) Locations.psiFile(project, args.string("path")) else Locations.declarationAt(project, args)

    fun name(element: PsiElement): String = (element as? PsiNamedElement)?.name ?: Locations.kind(element)

    suspend fun usages(refactoring: Refactoring): Array<UsageInfo> = try {
        smartReadAction(project) { refactoring.findUsages() }
    } catch (e: IndexNotReadyException) {
        throw ToolException("the IDE is still indexing; retry in a moment", e)
    }

    suspend fun rows(usages: Collection<UsageInfo>, max: Int): UsageRows = readAction {
        val located = usages.mapNotNull { it.element }
        UsageRows(
            located.take(max).map { Locations.describe(project, it) },
            located.mapNotNull { it.containingFile?.virtualFile }.distinct().size,
            located.size > max,
        )
    }

    suspend fun perform(refactoring: Refactoring, usages: Array<UsageInfo>) {
        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                val ref = Ref(usages)
                if (!refactoring.preprocessUsages(ref)) throw ToolException("the IDE cancelled the refactoring")
                refactoring.doRefactoring(ref.get())
            }
        }
        saveAll()
    }

    suspend fun saveAll() = edtWriteAction { FileDocumentManager.getInstance().saveAllDocuments() }
}
