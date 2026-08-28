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

@Service(Service.Level.PROJECT)
@State(name = "ClaudeCodeSettings", storages = [Storage("claude-code.xml")])
internal class LegacyProjectSettings : PersistentStateComponent<ClaudeSettings.State> {

    private val log = logger<LegacyProjectSettings>()
    private var state = ClaudeSettings.State()

    override fun getState(): ClaudeSettings.State = state
    override fun loadState(s: ClaudeSettings.State) = XmlSerializerUtil.copyBean(s, state)

    fun migrate(project: Project, scope: SettingsScope) {
        val adopted = SettingsStore.migrateFrom(scope, state)
        if (!adopted && !SettingsStore.storedAnywhere(scope)) return
        deleteProjectFile(project, adopted)
    }

    private fun deleteProjectFile(project: Project, adopted: Boolean) {
        val base = project.basePath ?: return
        val file = Paths.get(base, ".idea", "claude-code.xml")
        if (!Files.exists(file)) return
        runCatching { Files.delete(file) }
            .onSuccess {
                val why = if (adopted) "after adopting it into this project's settings" else "newer settings already exist"
                log.info("removed the legacy $file — $why")
            }
            .onFailure { log.warn("could not remove the legacy settings file $file", it) }
    }

    companion object {
        fun getInstance(project: Project): LegacyProjectSettings = project.service()
    }
}
