package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder

internal object ForgeActionPrompt {

    fun confirmMerge(project: Project, number: Long, title: String, target: String?): Boolean =
        MessageDialogBuilder
            .yesNo("Merge this?", mergeBody(number, title, target))
            .yesText("Merge it")
            .noText("Cancel")
            .ask(project)

    fun confirmOpen(project: Project, source: String, target: String): Boolean =
        MessageDialogBuilder
            .yesNo("Open a request from $source?", openBody(source, target))
            .yesText("Open it")
            .noText("Cancel")
            .ask(project)

    private fun mergeBody(number: Long, title: String, target: String?): String {
        val into = target?.let { " into $it" }.orEmpty()
        return "#$number $title\n\n" +
            "This merges the request$into on the forge, for everyone, right now. Whatever it contains " +
            "becomes part of that branch and whatever runs on that branch will run.\n\n" +
            "There is no undo."
    }

    private fun openBody(source: String, target: String): String =
        "$source → $target\n\n" +
            "This opens the request on the forge, where your team sees it and any pipeline configured for " +
            "it starts. Check the target branch is the one you meant: a request aimed at the wrong branch " +
            "is noise everyone has to read."
}
