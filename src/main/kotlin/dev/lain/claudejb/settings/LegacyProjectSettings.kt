package dev.lain.claudejb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Reads the settings where they used to live — `.idea/claude-code.xml` — and hands them over exactly once.
 *
 * **Migrate, then clean up, in that order.** [SettingsStore] holds the settings now, globally, and without
 * this class every user upgrading to 5.5.0 would open the IDE with default settings: no model, no permission
 * mode, no allowed tools, no binary path. So the old component is still declared (same name, same storage,
 * which is what makes the platform hand us the file that is on disk) and read once.
 *
 * **The first project to migrate wins.** Settings are global now, so if several projects each carry their
 * own `claude-code.xml`, only the first one adopted becomes the global set; the rest are removed without
 * being adopted. That is the user's decision, made knowingly: one model, one mode, one set of tools.
 *
 * Nothing is deleted until the new location holds the data — [migrate] removes the project file only after
 * [SettingsStore] reports that it exists.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSettings", storages = [Storage("claude-code.xml")])
internal class LegacyProjectSettings : PersistentStateComponent<ClaudeSettings.State> {

    private val log = logger<LegacyProjectSettings>()
    private var state = ClaudeSettings.State()

    override fun getState(): ClaudeSettings.State = state
    override fun loadState(s: ClaudeSettings.State) = XmlSerializerUtil.copyBean(s, state)

    /**
     * Adopts this project's old settings when nothing has been written globally yet, then removes the old
     * file either way.
     *
     * **Once the global settings exist, they are THE settings.** A project's leftover `claude-code.xml` is
     * not a second opinion to be reconciled at every start — it is a file from a previous version, and
     * leaving it around means every reinstall gets another chance to let it speak. So it goes, adopted or
     * not.
     *
     * That is safe because of what [SettingsStore.migrateFrom] refuses to do: it never overwrites an
     * existing global file, and it never creates one from a legacy state that carries nothing. The way a
     * configuration actually got lost was not the delete — it was a state of pure defaults being written as
     * if it were a migration, which made the real one, in a project opened later, arrive too late to matter.
     */
    fun migrate(project: Project) {
        val adopted = SettingsStore.migrateFrom(state)
        if (!adopted && !SettingsStore.exists()) return // nothing written anywhere yet: keep it, try later
        deleteProjectFile(project, adopted)
    }

    private fun deleteProjectFile(project: Project, adopted: Boolean) {
        val base = project.basePath ?: return
        val file = Paths.get(base, ".idea", "claude-code.xml")
        if (!Files.exists(file)) return
        runCatching { Files.delete(file) }
            .onSuccess {
                val why = if (adopted) "after adopting it globally" else "the global settings already exist"
                log.info("removed the legacy $file — $why")
            }
            .onFailure { log.warn("could not remove the legacy settings file $file", it) }
    }

    companion object {
        fun getInstance(project: Project): LegacyProjectSettings = project.service()
    }
}
