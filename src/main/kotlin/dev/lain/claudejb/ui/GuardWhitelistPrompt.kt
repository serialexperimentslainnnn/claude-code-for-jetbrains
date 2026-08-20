package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.lain.claudejb.permission.SecurityRule

internal object GuardWhitelistPrompt {

    fun confirm(project: Project, rule: SecurityRule, command: String): Boolean {
        if (rule.whitelistable) return true
        return MessageDialogBuilder
            .yesNo("Whitelist this command?", body(rule, command))
            .yesText("Whitelist it")
            .noText("Cancel")
            .ask(project)
    }

    private fun body(rule: SecurityRule, command: String) =
        "$command\n\n" +
            "${rule.label} stopped this because ${rule.blockedWhy.replaceFirstChar { it.lowercase() }}\n\n" +
            "Whitelisting it means that exact command runs without a card, in this project, until you remove " +
            "it from Settings ▸ Claude Code Security. Every other rule still judges it, and every other " +
            "command is unaffected."
}
