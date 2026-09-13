package dev.lain.claudejb.controller.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import javax.swing.SwingUtilities

internal object FocusKeeper {

    suspend fun <T> keep(project: Project, reveal: () -> T): T = withContext(Dispatchers.EDT) { keeping(project, reveal) }

    fun <T> keeping(project: Project, reveal: () -> T): T {
        val manager = IdeFocusManager.getInstance(project)
        val owner = manager.focusOwner
        val result = reveal()
        if (owner != null) later { later { restore(manager, owner) } }
        return result
    }

    private fun later(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.nonModal())

    private fun restore(manager: IdeFocusManager, owner: Component) {
        val now = manager.focusOwner ?: return
        if (now === owner || !owner.isShowing) return
        if (SwingUtilities.getWindowAncestor(now) !== SwingUtilities.getWindowAncestor(owner)) return
        manager.requestFocus(owner, false)
    }
}
