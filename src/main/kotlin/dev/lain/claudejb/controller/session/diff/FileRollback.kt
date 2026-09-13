package dev.lain.claudejb.controller.session.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.diff.EditSnapshot

object FileRollback {

    private const val UNDO_NAME = "Revert Claude's Edit"

    fun revert(project: Project, snapshot: EditSnapshot): Boolean {
        if (!DiffPresenter.isWithinRoot(snapshot.filePath, project.basePath)) return false
        var wrote = false
        val wasCreation = !snapshot.existedBefore
        runCatching {
            WriteCommandAction.writeCommandAction(project).withName(UNDO_NAME).run<Throwable> {
                val lfs = LocalFileSystem.getInstance()
                val vf = lfs.refreshAndFindFileByPath(snapshot.filePath) ?: lfs.findFileByPath(snapshot.filePath)
                when {
                    wasCreation -> {
                        if (vf != null && vf.exists()) vf.delete(this)
                        wrote = true
                    }

                    vf != null -> {
                        VfsUtil.saveText(vf, snapshot.beforeText)
                        wrote = true
                    }

                    snapshot.beforeText.isEmpty() -> wrote = true
                }
            }
        }
        return wrote
    }
}
