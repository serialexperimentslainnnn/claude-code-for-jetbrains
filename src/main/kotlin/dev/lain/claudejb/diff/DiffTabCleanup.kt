package dev.lain.claudejb.diff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

internal class DiffTabCleanup : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            OpenedDiffsService.getInstance(project).closeAll()
        }
    }
}
