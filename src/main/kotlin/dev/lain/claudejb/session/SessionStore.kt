package dev.lain.claudejb.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object SessionStore {

    private val projects: Path get() = Paths.get(System.getProperty("user.home"), ".claude", "projects")

    fun projectDir(basePath: String): Path? {
        if (basePath.isBlank()) return null
        val dir = projects.resolve(encodePath(basePath))
        return if (Files.isDirectory(dir)) dir else null
    }

    fun encodePath(basePath: String): String = basePath.replace(Regex("[^a-zA-Z0-9]"), "-")

    private val SAFE_ID = Regex("[A-Za-z0-9-]+")

    fun locate(sessionId: String): Path? {
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

    fun exists(sessionId: String): Boolean = locate(sessionId) != null

    fun readLines(sessionId: String): List<String>? =
        locate(sessionId)?.let { runCatching { Files.readAllLines(it) }.getOrNull() }

    fun sessionDir(sessionId: String): Path? {
        val transcript = locate(sessionId) ?: return null
        val dir = transcript.resolveSibling(sessionId)
        return if (Files.isDirectory(dir)) dir else null
    }

    fun subagentsDir(sessionId: String): Path? =
        sessionDir(sessionId)?.resolve(SUBAGENTS)?.takeIf { Files.isDirectory(it) }

    private const val SUBAGENTS = "subagents"

    fun listFiles(basePath: String): List<Path> {
        val dir = projectDir(basePath) ?: return emptyList()
        return runCatching {
            Files.newDirectoryStream(dir, "*.jsonl").use { it.toList() }
        }.getOrDefault(emptyList())
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
    }
}
