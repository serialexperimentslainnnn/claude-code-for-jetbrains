package dev.lain.claudejb.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

fun edt(project: Project, block: () -> Unit) = edt { if (!project.isDisposed) block() }

fun edtNow(block: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) block() else app.invokeLater(block, ModalityState.any())
}
