package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.lain.claudejb.permission.SecurityRule

/**
 * The one question asked before a command is whitelisted from a block — and it is a question, never a refusal.
 *
 * Every rule can be whitelisted, including the ones that stop credential reads and paths off this machine:
 * a false positive the user cannot get past stops work they asked for, and which of their own commands they
 * are willing to permit is their call. What [SecurityRule.whitelistable] still decides is whether they are
 * told what they are permitting first — for a rule marked liftable this is a single click, and for the rest
 * it costs one dialog that states the rule's own reason back to them.
 */
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
