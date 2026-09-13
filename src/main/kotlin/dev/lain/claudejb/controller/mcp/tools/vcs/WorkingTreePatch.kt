package dev.lain.claudejb.controller.mcp.tools.vcs

import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import dev.lain.claudejb.model.mcp.ToolException
import java.io.StringWriter

internal object WorkingTreePatch {

    class Unified(val files: Int, val text: String)

    fun unified(project: Project, path: String?): Unified {
        val manager = ChangeListManager.getInstance(project)
        val changes = if (path == null) manager.allChanges else manager.getChangesIn(VcsPaths.filePath(project, path))
        val patches = try {
            IdeaTextPatchBuilder.buildPatch(project, changes, VcsPaths.base(project), false)
        } catch (e: VcsException) {
            throw ToolException("the IDE could not build the diff: ${e.message}", e)
        }
        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        return Unified(patches.size, stripIdeHeaders(writer.toString()))
    }

    fun stripIdeHeaders(patch: String): String =
        patch.lineSequence().filterNot { line -> IDE_HEADERS.any { line.startsWith(it) } }.joinToString("\n")

    private val IDE_HEADERS = listOf("Index: ", "IDEA additional info:", "Subsystem: ", "<+>", "====")
}
