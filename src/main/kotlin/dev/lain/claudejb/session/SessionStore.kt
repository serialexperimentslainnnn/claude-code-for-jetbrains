package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Read-only access to the `claude` binary's own session transcripts — the single source of truth for
 * past conversations. The binary stores one JSONL file per session under
 * `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`. We never duplicate that content; readers above
 * ([SessionTitleReader], [SessionTranscriptReader]) parse these files on demand.
 *
 * All access is best-effort and confined to `~/.claude/projects`; every call tolerates a missing/locked
 * tree and returns null/empty rather than throwing. IO is blocking — call off the EDT.
 */
internal object SessionStore {

    private val projects: Path get() = Paths.get(System.getProperty("user.home"), ".claude", "projects")

    /**
     * The binary's transcript directory for a project at [basePath]. The binary derives the folder name by
     * replacing every non-alphanumeric character of the absolute cwd with `-` (e.g. `/home/u/My.Proj` →
     * `-home-u-My-Proj`). Returns null when the directory doesn't exist.
     */
    fun projectDir(basePath: String): Path? {
        if (basePath.isBlank()) return null
        val dir = projects.resolve(encodePath(basePath))
        return if (Files.isDirectory(dir)) dir else null
    }

    /** The binary's folder-name encoding of an absolute cwd: every non-alphanumeric char → `-`. Pure — testable. */
    fun encodePath(basePath: String): String = basePath.replace(Regex("[^a-zA-Z0-9]"), "-")

    /** A session id is a binary-issued UUID: hex groups + dashes. Guards file lookups against path traversal. */
    private val SAFE_ID = Regex("[A-Za-z0-9-]+")

    /** Locates `<sessionId>.jsonl` under any project dir (by its unique UUID — no cwd encoding needed). */
    fun locate(sessionId: String): Path? {
        // Confinement: reject anything that isn't a plain id, so a crafted value can't escape the tree.
        if (!SAFE_ID.matches(sessionId) || !Files.isDirectory(projects)) return null
        return runCatching {
            Files.newDirectoryStream(projects).use { dirs ->
                dirs.asSequence()
                    .filter { Files.isDirectory(it) }
                    .map { it.resolve("$sessionId.jsonl") }
                    .firstOrNull { Files.isRegularFile(it) }
            }
        }.getOrNull()
    }

    /** Whether the binary still has a transcript for [sessionId]. */
    fun exists(sessionId: String): Boolean = locate(sessionId) != null

    /** Raw JSONL lines for [sessionId], or null if the file is absent/unreadable. */
    fun readLines(sessionId: String): List<String>? =
        locate(sessionId)?.let { runCatching { Files.readAllLines(it) }.getOrNull() }

    /** Session transcript files for the project at [basePath], newest-first. Empty if the dir is absent. */
    fun listFiles(basePath: String): List<Path> {
        val dir = projectDir(basePath) ?: return emptyList()
        return runCatching {
            Files.newDirectoryStream(dir, "*.jsonl").use { it.toList() }
        }.getOrDefault(emptyList())
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
    }
}
