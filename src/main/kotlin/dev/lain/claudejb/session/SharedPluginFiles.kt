package dev.lain.claudejb.session

import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SettingsScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object SharedPluginFiles {

    private val log = logger<SharedPluginFiles>()

    @Synchronized
    fun migrate(basePath: String?) {
        if (SecretStore.inert()) return
        val targets = (knownProjects() + listOfNotNull(basePath?.takeIf { it.isNotBlank() })).distinct()
        if (targets.isEmpty()) return
        migrateOpenChats(targets)
        migrateAgentIndex(targets)
    }

    private fun knownProjects(): List<String> = buildList {
        runCatching { ProjectManager.getInstance().openProjects.mapNotNullTo(this) { it.basePath } }
        runCatching {
            RecentProjectListActionProvider.getInstance().getActions()
                .filterIsInstance<ReopenProjectAction>()
                .mapTo(this) { it.projectPath }
        }
    }.filter { it.isNotBlank() }.distinct()

    private fun migrateOpenChats(targets: List<String>) {
        val file = fileOf(OPEN_CHATS) ?: return
        val all = SessionHistory.decode(read(file)).toMutableMap()
        var took = false
        targets.forEach { basePath ->
            val slice = all.remove(SessionStore.encodePath(basePath)) ?: return@forEach
            took = true
            adopt(SettingsScope.ofPath(basePath).openChatsName) { SessionHistory.encodeIds(slice) }
        }
        if (took) prune(file, all.isEmpty()) { SessionHistory.encode(all) }
    }

    private fun migrateAgentIndex(targets: List<String>) {
        val file = fileOf(AGENT_INDEX) ?: return
        val all = PluginAgentIndex.decode(read(file))
        var took = false
        targets.forEach { basePath ->
            val mine = sessionIdsUnder(basePath)
            val slice = all.filterKeys { it in mine }
            if (slice.isEmpty()) return@forEach
            took = true
            slice.keys.forEach { all.remove(it) }
            adopt(SettingsScope.ofPath(basePath).agentIndexName) { PluginAgentIndex.encode(slice) }
        }
        val orphans = all.keys.filterNot { SessionStore.exists(it) }
        if (orphans.isNotEmpty()) {
            took = true
            orphans.forEach { all.remove(it) }
        }
        if (took) prune(file, all.isEmpty()) { PluginAgentIndex.encode(all) }
    }

    private fun adopt(name: String, body: () -> String) {
        if (SecretStore.get(name) != null) return
        SecretStore.set(name, body())
    }

    private fun prune(file: Path, empty: Boolean, body: () -> String) {
        runCatching {
            if (empty) Files.deleteIfExists(file) else Files.writeString(file, body())
        }.onFailure {
            log.warn("could not prune ${file.fileName} after migrating out of it", it)
        }
    }

    private fun sessionIdsUnder(basePath: String): Set<String> =
        SessionStore.listFiles(basePath)
            .map { it.fileName.toString().removeSuffix(JSONL) }
            .toSet()

    private fun read(file: Path): String = runCatching { Files.readString(file) }.getOrNull().orEmpty()

    private fun fileOf(name: String): Path? = PluginAgentIndex.homeDir()
        ?.let { Paths.get(it) }
        ?.resolve(DIR_IDE)
        ?.resolve(DIR_PLUGIN)
        ?.resolve(name)
        ?.takeIf { Files.isRegularFile(it) }

    private const val DIR_IDE = "ide"
    private const val DIR_PLUGIN = "claude-code-native"
    private const val JSONL = ".jsonl"

    const val OPEN_CHATS = "open-chats.json"
    const val AGENT_INDEX = "agent-index.json"
}
