package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import dev.lain.claudejb.settings.OtherIdeConfigs
import dev.lain.claudejb.settings.SettingsScope
import dev.lain.claudejb.settings.SettingsTransfer
import javax.swing.JComponent

/**
 * *Migrate from another IDE…* — copy this machine's other JetBrains IDEs' Claude Code configuration here.
 *
 * The case it is for is the ordinary one: the same project open in IntelliJ and in PyCharm, configured once.
 * JetBrains' *Import Settings* copies configuration directories and never touches the keychain, which is
 * where all of this lives, so without a gesture like this one a freshly imported IDE starts empty.
 *
 * Direction is one-way, into the IDE you are sitting in. The reverse is the same code with the scopes
 * swapped and can be added if it is ever wanted; configuring IDE B from IDE A is the rare case and is not
 * assumed.
 */
internal class MigrateFromIdeDialog(private val project: Project) : DialogWrapper(project) {

    private val installations = OtherIdeConfigs.others()

    private val ideCombo = ComboBox(installations.toTypedArray()).apply {
        renderer = labelRenderer { (it as? OtherIdeConfigs.Installation)?.name }
        addActionListener { reloadProjects() }
    }

    private val projectList = CheckBoxList<String>()

    private val parts = SettingsTransfer.Part.entries.associateWith { part ->
        JBCheckBox(part.label, part != SettingsTransfer.Part.ALERT_LOG)
    }

    /** How many projects actually received something, so the caller can say so rather than guess. */
    var migrated: Int = 0
        private set

    init {
        title = TITLE
        setOKButtonText("Migrate")
        init()
        reloadProjects()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("From:") { cell(ideCombo).align(AlignX.FILL) }
        row("Projects:") { scrollCell(projectList).align(Align.FILL) }
            .resizableRow()
            .rowComment(PROJECTS_NOTE, MAX_LINE_LENGTH_WORD_WRAP)
        group("What to copy") {
            SettingsTransfer.Part.entries.forEach { part -> row { cell(parts.getValue(part)) } }
        }
        row { comment(NOTE, MAX_LINE_LENGTH_WORD_WRAP) }
    }

    /**
     * Only the projects that IDE actually has settings for, and all of them ticked.
     *
     * Everything it has ever opened would offer copies that do nothing, so the list is filtered by probing
     * the source scope first — and everything that survives that filter is worth taking, so nothing is left
     * for the user to tick one at a time. The list has to come from the other IDE's recent projects because
     * **the PasswordSafe cannot be enumerated**: a scope id can be computed from a configuration path and a
     * project path and then probed, but there is no way to ask which entries exist.
     */
    private fun reloadProjects() {
        val installation = selected() ?: return
        val candidates = (
            OtherIdeConfigs.recentProjects(installation) + listOfNotNull(project.basePath)
            ).distinct().sorted()
        val offered = candidates.filter { SettingsTransfer.holdsSettings(scopeIn(installation, it)) }
        projectList.setItems(offered) { it }
        offered.forEach { projectList.setItemSelected(it, true) }
    }

    private fun selected() = ideCombo.selectedItem as? OtherIdeConfigs.Installation

    private fun scopeIn(installation: OtherIdeConfigs.Installation, basePath: String) =
        SettingsScope.of(installation.configPath, basePath)

    private fun chosenProjects(): List<String> =
        (0 until projectList.itemsCount).mapNotNull { at ->
            projectList.getItemAt(at)?.takeIf { projectList.isItemSelected(at) }
        }

    private fun chosenParts(): Set<SettingsTransfer.Part> =
        parts.filterValues { it.isSelected }.keys

    override fun doValidate(): ValidationInfo? = when {
        installations.isEmpty() -> ValidationInfo("No other JetBrains IDE has been started on this machine.")
        chosenProjects().isEmpty() -> ValidationInfo("Pick at least one project.", projectList)
        chosenParts().isEmpty() -> ValidationInfo("Pick at least one thing to copy.")
        else -> null
    }

    override fun doOKAction() {
        val installation = selected() ?: return
        val wanted = chosenParts()
        migrated = chosenProjects().count { path ->
            SettingsTransfer.copyScope(scopeIn(installation, path), SettingsScope.ofPath(path), wanted)
        }
        super.doOKAction()
    }

    companion object {
        const val TITLE = "Migrate Claude Code Settings from Another IDE"

        private const val PROJECTS_NOTE =
            "Only projects that IDE actually has Claude Code settings for, taken from its own recent-projects " +
                "list. All of them are ticked; untick what you do not want."

        private const val NOTE =
            "Every JetBrains IDE shares one keychain, so this copies from one encrypted entry to another " +
                "without anything leaving it — environment variables included, unlike an exported file. What " +
                "separates them is the scope: an entry is keyed by the IDE's configuration directory and the " +
                "project, so this IDE has no entry for a project until something writes one. The other IDE is " +
                "only read, and what you do not tick is left exactly as it is here."
    }
}
