package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder

internal object IdeActionPrompt {

    fun confirmed(project: Project, action: GitActionCatalog.GitAction): Boolean {
        val warning = action.warning ?: return true
        return MessageDialogBuilder
            .yesNo("${action.label}?", "$warning\n\nThere is no undo.")
            .yesText(action.label)
            .noText("Cancel")
            .ask(project)
    }
}
