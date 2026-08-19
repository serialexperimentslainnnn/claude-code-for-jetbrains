package dev.lain.claudejb.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

object FileRollback {

    fun revert(project: Project, snapshot: EditSnapshot): Boolean {
        if (!DiffPresenter.isWithinRoot(snapshot.filePath, project.basePath)) return false
        var wrote = false
        val wasCreation = !snapshot.existedBefore
        runCatching {
            WriteCommandAction.runWriteCommandAction(project) {
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
