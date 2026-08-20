package dev.lain.claudejb.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SettingsTransfer
import java.nio.file.Path
import javax.swing.JButton

/**
 * Taking this project's configuration somewhere else, and bringing somebody else's here.
 *
 * Three buttons rather than anything automatic. The two file ones are the portable route — another machine,
 * a colleague, a backup — and the third is the one that will actually get used: the same project already
 * configured in another IDE on this box.
 *
 * It owns no setting, so [reset], [apply] and [changedFields] have nothing to do: each button acts when it
 * is pressed, and tells the page to redraw when it changed something underneath it.
 */
internal class SettingsTransferSection(
    private val project: Project,
    private val onChanged: () -> Unit,
) : SettingsSection {

    private val exportButton = JButton("Export settings…").apply {
        addActionListener { onExport() }
    }

    private val importButton = JButton("Import settings…").apply {
        addActionListener { onImport() }
    }

    private val migrateButton = JButton("Migrate from another IDE…").apply {
        addActionListener { onMigrate() }
    }

    override fun addTo(panel: Panel) {
        panel.group("Transfer") {
            row {
                cell(exportButton)
                cell(importButton)
                cell(migrateButton)
            }.rowComment(NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        }
    }

    override fun reset(s: ClaudeSettings.State) = Unit

    override fun apply(s: ClaudeSettings.State) = Unit

    override fun changedFields(s: ClaudeSettings.State): List<Boolean> = emptyList()

    private fun onExport() {
        val descriptor = FileSaverDescriptor(
            "Export Claude Code Settings",
            "Write this project's plugin configuration to a file",
            SettingsTransfer.EXTENSION,
        )
        // The Path overload, named explicitly: the VirtualFile one takes the same shape of null.
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as Path?, SettingsTransfer.FILE_NAME) ?: return
        val body = SettingsTransfer.export(ClaudeSettings.getInstance(project).state)
        runCatching { target.file.writeText(body) }
            .onFailure { report("Could not write ${target.file.name}: ${it.message}") }
    }

    private fun onImport() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withFileFilter { it.fileType === FileTypes.PLAIN_TEXT || it.extension == SettingsTransfer.EXTENSION }
            .withTitle("Import Claude Code Settings")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val body = runCatching { String(chosen.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
        val incoming = body?.let { SettingsTransfer.import(it) }
        if (incoming == null) {
            report("${chosen.name} is not a Claude Code settings file.")
            return
        }
        if (!confirm(IMPORT_TITLE, IMPORT_BODY, "Import")) return
        val settings = ClaudeSettings.getInstance(project)
        // Withheld from the file by construction, so an import must leave whatever is already here alone
        // rather than blanking it with the default the decode produced.
        incoming.envVars = settings.state.envVars
        settings.replaceState(incoming)
        settings.save()
        onChanged()
    }

    private fun onMigrate() {
        val dialog = MigrateFromIdeDialog(project)
        if (!dialog.showAndGet()) return
        ClaudeSettings.getInstance(project).reload { onChanged() }
        report(
            when (dialog.migrated) {
                0 -> "Nothing was copied."
                1 -> "Copied the settings of 1 project."
                else -> "Copied the settings of ${dialog.migrated} projects."
            },
        )
    }

    private fun confirm(title: String, body: String, yes: String) = MessageDialogBuilder
        .yesNo(title, body)
        .yesText(yes)
        .noText("Cancel")
        .ask(project)

    private fun report(message: String) =
        Messages.showInfoMessage(project, message, MigrateFromIdeDialog.TITLE)

    private companion object {
        const val IMPORT_TITLE = "Import Claude Code Settings"

        const val IMPORT_BODY =
            "Replace this project's Claude Code settings with the ones in that file?\n\n" +
                "Everything on both pages is overwritten, including the Sensitive Guard's rules and " +
                "whitelists. Your environment variables are kept as they are — a settings file never " +
                "carries them.\n\n" +
                "There is no undo."

        const val NOTE =
            "An exported file <b>never carries your environment variables</b>: that is where an API key or a " +
                "credentialed proxy URL ends up, and it is the reason these settings live in the keychain " +
                "rather than in the project. Provider keys and Git host tokens are not in this document at all. " +
                "<b>Migrate from another IDE</b> copies keychain to keychain on this machine, so there it all " +
                "travels. A permission mode that would weaken security is refused on the way in, whichever " +
                "route it takes."
    }
}
