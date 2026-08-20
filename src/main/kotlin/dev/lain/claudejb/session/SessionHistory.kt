package dev.lain.claudejb.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Service(Service.Level.PROJECT)
class SessionHistory(private val project: Project) {

    private val log = logger<SessionHistory>()

    private val scope: SettingsScope get() = SettingsScope.of(project)

    @Synchronized
    fun setOpenSessions(ids: List<String>) {
        SecretStore.set(scope.openChatsName, encodeIds(ids.filter { it.isNotBlank() }))
    }

    @Synchronized
    fun openSessions(): List<String> {
        stored()?.let { return it }
        SharedPluginFiles.migrate(project.basePath)
        stored()?.let { return it }
        return adoptFromWorkspace()
    }

    private fun stored(): List<String>? = SecretStore.get(scope.openChatsName)?.let { decodeIds(it) }

    private fun adoptFromWorkspace(): List<String> {
        val legacy = runCatching { LegacySessionHistory.getInstance(project).openSessions() }
            .getOrDefault(emptyList())
        if (legacy.isEmpty()) return emptyList()
        log.info("migrating ${legacy.size} open chat(s) out of workspace.xml")
        setOpenSessions(legacy)
        return legacy
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun getInstance(project: Project): SessionHistory = project.service()

        fun encode(all: Map<String, List<String>>): String =
            runCatching { JSON.encodeToString(all) }.getOrDefault("")

        fun decode(text: String): Map<String, List<String>> {
            if (text.isBlank()) return emptyMap()
            return runCatching { JSON.decodeFromString<Map<String, List<String>>>(text) }
                .getOrDefault(emptyMap())
        }

        fun encodeIds(list: List<String>): String =
            runCatching { JSON.encodeToString(list) }.getOrDefault("")

        fun decodeIds(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return runCatching { JSON.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
        }
    }
}
