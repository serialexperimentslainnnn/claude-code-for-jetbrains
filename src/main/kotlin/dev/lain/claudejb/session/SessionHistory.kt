package dev.lain.claudejb.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class SessionHistory(private val project: Project) {

    private val log = logger<SessionHistory>()

    @Synchronized
    fun setOpenSessions(ids: List<String>) {
        val key = projectKey() ?: return
        val all = readAll().toMutableMap()
        all[key] = ids.filter { it.isNotBlank() }
        write(all)
    }

    @Synchronized
    fun openSessions(): List<String> {
        val key = projectKey() ?: return emptyList()
        readAll()[key]?.let { return it }
        val legacy = runCatching { LegacySessionHistory.getInstance(project).openSessions() }
            .getOrDefault(emptyList())
        if (legacy.isNotEmpty()) {
            log.info("migrating ${legacy.size} open chat(s) from workspace.xml to ~/.claude")
            setOpenSessions(legacy)
        }
        return legacy
    }

    private fun projectKey(): String? = project.basePath?.takeIf { it.isNotBlank() }?.let(SessionStore::encodePath)

    private fun readAll(): Map<String, List<String>> {
        val file = indexFile() ?: return emptyMap()
        val body = runCatching { Files.readString(file) }.getOrNull().orEmpty()
        return decode(body)
    }

    private fun write(all: Map<String, List<String>>) {
        val file = indexFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, encode(all))
        }.onFailure {
            log.warn("could not persist the open-chat list to ${file.parent}", it)
        }
    }

    private fun indexFile(): Path? = PluginAgentIndex.homeDir()?.let { Paths.get(it) }
        ?.resolve(DIR_IDE)?.resolve(DIR_PLUGIN)?.resolve(FILE)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val DIR_IDE = "ide"
        private const val DIR_PLUGIN = "claude-code-native"
        private const val FILE = "open-chats.json"

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
