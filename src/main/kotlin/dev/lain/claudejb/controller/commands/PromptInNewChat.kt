package dev.lain.claudejb.controller.commands

import com.intellij.openapi.project.Project
import dev.lain.claudejb.view.window.ClaudeToolWindowFactory

internal object PromptInNewChat {

    fun open(project: Project, title: String, prompt: String): Boolean {
        val commands = ClaudeToolWindowFactory.chatTabs(project)?.commands ?: return false
        commands.newChatWith(title, prompt)
        return true
    }

    fun title(action: String, subject: String): String {
        val clean = subject
            .map { if (isRenderable(it)) it else ' ' }
            .joinToString("")
            .replace(RUN_OF_SPACES, " ")
            .trim()
        if (clean.isEmpty()) return action
        return action + ": " + TabSessionCommands.truncate(clean, SUBJECT_MAX)
    }

    private fun isRenderable(ch: Char): Boolean =
        !Character.isISOControl(ch) && Character.getType(ch) !in SEPARATORS

    private val RUN_OF_SPACES = Regex(" {2,}")

    private val SEPARATORS = setOf(Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())

    private const val SUBJECT_MAX = 32
}
