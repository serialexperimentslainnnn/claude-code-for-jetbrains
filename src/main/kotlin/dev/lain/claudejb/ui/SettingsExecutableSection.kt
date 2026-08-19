package dev.lain.claudejb.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
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

    override fun addTo(form: FormBuilder): FormBuilder = form
        .addSeparator()
        .addLabeledComponent("claude executable path:", claudePathField)
        .addLabeledComponent("node executable path:", nodePathField)
        .addLabeledComponent("Source script:", sourceScriptField)
        .addComponent(sourceScriptWarningLabel())
        .addComponent(sectionLabel("Environment variables (KEY=VALUE per line)"))
        .addComponent(JBScrollPane(envVarsArea))
        .addComponent(envVarsWarningLabel())

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

    private fun envVarsWarningLabel() = noteLabel(
        "These variables are stored in the IDE <b>password safe</b> (your OS keychain), like every other secret " +
            "the plugin holds — since 5.5.0 they are no longer written to <code>.idea/claude-code.xml</code>. " +
            "⚠ They are still handed to the <code>claude</code> process, so anything you put here is readable by " +
            "the agent and by whatever it runs.",
    )

    private fun sourceScriptWarningLabel() = noteLabel(
        "⚠ <b>Security:</b> this script is <b>executed</b> when the session starts. Only point it at a script " +
            "you trust — do not run scripts that arrive with an untrusted project/repo.",
    )

    private companion object {
        const val ENV_VARS_ROWS = 4
    }
}
