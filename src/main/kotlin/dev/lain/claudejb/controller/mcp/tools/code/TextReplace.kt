package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import dev.lain.claudejb.model.mcp.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

internal class TextReplace(private val project: Project) {

    class Change(val file: VirtualFile, val replaced: Int)

    fun model(query: String, replacement: String, regex: Boolean, caseSensitive: Boolean, directory: String?): FindModel =
        FindModel().apply {
            stringToFind = query
            stringToReplace = replacement
            isRegularExpressions = regex
            isCaseSensitive = caseSensitive
            isReplaceState = true
            isProjectScope = directory == null
            isWithSubdirectories = true
            directoryName = directory
        }

    suspend fun filesMatching(model: FindModel, max: Int): List<VirtualFile> {
        val files = Collections.synchronizedSet(LinkedHashSet<VirtualFile>())
        withContext(Dispatchers.IO) {
            try {
                coroutineToIndicator { indicator ->
                    FindInProjectUtil.findUsages(model, project, indicator, PRESENTATION, emptySet()) { info ->
                        info.virtualFile?.let { files += it }
                        files.size < max
                    }
                }
            } catch (e: IndexNotReadyException) {
                throw ToolException("the IDE is still indexing; retry in a moment", e)
            }
        }
        return synchronized(files) { files.toList() }
    }

    suspend fun replaceIn(file: VirtualFile, model: FindModel): Change {
        val replaced = writeCommandAction(project, "Claude: replace in ${file.name}") {
            val document = Locations.document(project, file)
            replaceAll(document, model, file)
        }
        return Change(file, replaced)
    }

    private fun replaceAll(document: Document, model: FindModel, file: VirtualFile): Int {
        val manager = FindManager.getInstance(project)
        var offset = 0
        var count = 0
        while (offset <= document.textLength) {
            val text = document.charsSequence
            val result = manager.findString(text, offset, model, file)
            if (!result.isStringFound) break
            val found = text.subSequence(result.startOffset, result.endOffset).toString()
            val replacement = try {
                manager.getStringToReplace(found, model, result.startOffset, text)
            } catch (e: FindManager.MalformedReplacementStringException) {
                throw ToolException("the replacement is not valid for this pattern: ${e.message}", e)
            }
            document.replaceString(result.startOffset, result.endOffset, replacement)
            count++
            offset = result.startOffset + replacement.length + if (result.endOffset == result.startOffset) 1 else 0
        }
        return count
    }

    private companion object {
        val PRESENTATION = FindUsagesProcessPresentation(UsageViewPresentation())
    }
}
