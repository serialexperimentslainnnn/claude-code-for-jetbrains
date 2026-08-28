package dev.lain.claudejb.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.settings.ClaudeSettings

internal class SettingsExecutableSection : SettingsSection {

    private val claudePathField = JBTextField().apply {
        emptyText.text = "Auto-detect (leave blank unless 'claude' is in a custom location)"
    }
    private val nodePathField = JBTextField().apply {
        emptyText.text = "Auto-detect (set only if Node is in a custom dir not on PATH — Windows npm installs)"
    }
    private val envVarsArea = JBTextArea(ENV_VARS_ROWS, 0).apply {
        emptyText.text = "One KEY=VALUE per line (e.g. PATH=C:\\custom\\bin;%PATH%). Useful on Windows."
    }
    private val sourceScriptField = JBTextField().apply {
        emptyText.text = "Optional: .sh to source (Linux/macOS) or PowerShell profile/.ps1 to dot-source (Windows)"
    }

    override fun addTo(panel: Panel) {
        panel.group("Executables") {
            row("claude executable path:") { cell(claudePathField).align(AlignX.FILL) }
            row("node executable path:") { cell(nodePathField).align(AlignX.FILL) }
            row("Source script:") { cell(sourceScriptField).align(AlignX.FILL) }
                .rowComment(SOURCE_SCRIPT_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
            row("Environment variables:") { scrollCell(envVarsArea).align(AlignX.FILL) }
                .rowComment(ENV_VARS_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    override fun reset(s: ClaudeSettings.State) {
        claudePathField.text = s.claudePath
        nodePathField.text = s.nodePath
        sourceScriptField.text = s.sourceScript
        envVarsArea.text = s.envVars
    }

    override fun apply(s: ClaudeSettings.State) {
        s.claudePath = claudePathField.text.trim()
        s.nodePath = nodePathField.text.trim()
        s.sourceScript = sourceScriptField.text.trim()
        s.envVars = envVarsArea.text
    }

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = listOf(
        claudePathField.text.trim() != s.claudePath,
        nodePathField.text.trim() != s.nodePath,
        sourceScriptField.text.trim() != s.sourceScript,
        envVarsArea.text != s.envVars,
    )

    private companion object {
        const val ENV_VARS_ROWS = 4

        const val SOURCE_SCRIPT_NOTE =
            "⚠ <b>Executed</b> when the session starts. Point it only at a script you trust — never one that " +
                "arrived with an untrusted repository."

        const val ENV_VARS_NOTE =
            "One <code>KEY=VALUE</code> per line. Stored in the IDE password safe, like every other secret the " +
                "plugin holds. ⚠ They are handed to the <code>claude</code> process, so anything here is readable " +
                "by the agent and by whatever it runs."
    }
}
