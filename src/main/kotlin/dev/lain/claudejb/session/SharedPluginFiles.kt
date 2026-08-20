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

/**
 * The two plaintext files the plugin used to keep under `~/.claude/ide/claude-code-native/`, and the one-way
 * trip out of them into the IDE's safe.
 *
 * **Why they have to move.** Both are keyed by *project*, not by IDE, and they sit in a directory belonging
 * to the machine — so every IDE on the box shared one file, and the plugin's own state was the only thing it
 * kept in the clear. Everything else it owns is in the keychain; these were the exception.
 *
 * **What it takes and what it leaves.** A sweep migrates the projects **this** IDE knows about — the ones
 * open now plus the ones in its recent list — and each of those gets its own slice written under its own
 * scope. Anything belonging to a project this IDE has never opened is left exactly where it is: it is
 * another installation's, and that installation will take it when it next runs. The file is rewritten
 * without what was taken, and deleted once nothing is left, so it empties itself as each IDE migrates
 * instead of lingering for ever.
 *
 * **The cost, stated rather than discovered.** A project opened in two IDEs has *one* entry, and the two do
 * not talk. The first to migrate takes it. For the open-chat list that means the other IDE restores the most
 * recent session instead of the exact set of tabs, once, and then writes its own; for the agent index it
 * means the past sessions of that project lose their agent tree in the other IDE. That was accepted
 * deliberately, in exchange for the file ever going away.
 *
 * Nothing is taken when the safe cannot hold it ([SecretStore.inert]) — deleting the only copy of something
 * into a store that is not writing would be the one unrecoverable way to get this wrong.
 */
internal object SharedPluginFiles {

    private val log = logger<SharedPluginFiles>()

    /**
     * Migrates [basePath] and every project this IDE knows, then prunes what it took.
     *
     * Safe to call on every read and deliberately keeps no "already done" flag: once the files are gone this
     * is two `isRegularFile` checks, and a caller only reaches it when its own scope is still empty. A flag
     * would buy nothing and would be process-global state no test could reset.
     */
    @Synchronized
    fun migrate(basePath: String?) {
        if (SecretStore.inert()) return
        val targets = (knownProjects() + listOfNotNull(basePath?.takeIf { it.isNotBlank() })).distinct()
        if (targets.isEmpty()) return
        migrateOpenChats(targets)
        migrateAgentIndex(targets)
    }

    /** Everything this installation could reasonably claim: what is open, and what it remembers opening. */
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
        // A session whose transcript no longer exists has nothing left to index, so it is not anyone's data.
        val orphans = all.keys.filterNot { SessionStore.exists(it) }
        if (orphans.isNotEmpty()) {
            took = true
            orphans.forEach { all.remove(it) }
        }
        if (took) prune(file, all.isEmpty()) { PluginAgentIndex.encode(all) }
    }

    /** Writes a slice into its scope, but never over one that is already there: the safe is the truth now. */
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
