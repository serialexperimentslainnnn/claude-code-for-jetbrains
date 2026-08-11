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

/**
 * Which chats were open, so they can be reopened on the next start.
 *
 * **Written by us, into `~/.claude`, and that is the point.** This lived in `workspace.xml` as a
 * `PersistentStateComponent`, which means the *platform* decides when it reaches disk — on its own save
 * cycle, at exit, if the exit is orderly. Reinstalling the plugin and restarting straight afterwards is
 * exactly the case where that write has not happened yet, and the symptom is the honest one: the last chat
 * you opened is not restored, while an older list is. Our own file is written the moment the set changes.
 *
 * Two more reasons it belongs here rather than in the project directory: `.idea/` is shared, synced and
 * committed by accident, and this is the user's conversation history; and the agent index
 * ([PluginAgentIndex]) already lives here, so restore reads one place instead of two that can disagree.
 *
 * **Ids only.** No transcript, no title, no prompt: the binary's own session files are the source of truth
 * and are re-read on restore. Keyed by the project's path, encoded the way [SessionStore] encodes it, so
 * several projects coexist in one file without knowing about each other.
 */
@Service(Service.Level.PROJECT)
class SessionHistory(private val project: Project) {

    private val log = logger<SessionHistory>()

    /** Records the currently-open tabs' session ids (in tab order) so they can be reopened on next startup. */
    @Synchronized
    fun setOpenSessions(ids: List<String>) {
        val key = projectKey() ?: return
        val all = readAll().toMutableMap()
        all[key] = ids.filter { it.isNotBlank() }
        write(all)
    }

    /**
     * Session ids of the tabs open at last save, in the stored order.
     *
     * Migrates on first read: a project that has nothing in our file but has the old `workspace.xml` entry
     * gets that list adopted and written here, once. Without it, everyone upgrading to 5.5.0 would lose
     * their open tabs on the first start — the opposite of what moving the file was for.
     */
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
            // Best-effort: the cost is the next start opening the wrong set of tabs, not lost data — the
            // conversations themselves are the binary's files. Logged because "my chats stopped coming
            // back" is otherwise unexplainable.
            log.warn("could not persist the open-chat list to ${file.parent}", it)
        }
    }

    /** `~/.claude/ide/claude-code-native/open-chats.json`, or null when there is no home to write into. */
    private fun indexFile(): Path? = PluginAgentIndex.homeDir()?.let { Paths.get(it) }
        ?.resolve(DIR_IDE)?.resolve(DIR_PLUGIN)?.resolve(FILE)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val DIR_IDE = "ide"
        private const val DIR_PLUGIN = "claude-code-native"
        private const val FILE = "open-chats.json"

        fun getInstance(project: Project): SessionHistory = project.service()

        /** Serializes the whole map (project → open session ids). Pure — unit-testable. */
        fun encode(all: Map<String, List<String>>): String =
            runCatching { JSON.encodeToString(all) }.getOrDefault("")

        /** Parses it back; blank or corrupt input yields an empty map rather than throwing. */
        fun decode(text: String): Map<String, List<String>> {
            if (text.isBlank()) return emptyMap()
            return runCatching { JSON.decodeFromString<Map<String, List<String>>>(text) }
                .getOrDefault(emptyMap())
        }

        /** Serializes an id list. Kept for the existing tests that pin the encoding. */
        fun encodeIds(list: List<String>): String =
            runCatching { JSON.encodeToString(list) }.getOrDefault("")

        /** Parses an id list; tolerates blank/corrupt input (→ empty, never throws). */
        fun decodeIds(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return runCatching { JSON.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
        }
    }
}
