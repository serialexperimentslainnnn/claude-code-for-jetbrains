package dev.lain.claudejb.settings

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The other JetBrains IDEs on this machine, and which projects each of them remembers.
 *
 * There is no API for "installed products", and this deliberately does not pretend there is: what can be
 * enumerated is the **configuration directory of every product that has actually started here**, which is
 * the only set that could hold scopes of ours anyway. They are the siblings of this IDE's own — under
 * `~/.config/JetBrains` on Linux, `~/Library/Application Support/JetBrains` on macOS,
 * `%APPDATA%/JetBrains` on Windows — and the directory name is the product and version
 * (`IntelliJIdea2025.3`, `PyCharm2025.3`).
 *
 * Nothing is guessed. A scope id is a digest of the configuration path and the project path, so the other
 * IDE's scope for a project is the same calculation with its configuration path substituted for ours.
 */
internal object OtherIdeConfigs {

    data class Installation(val name: String, val configPath: String)

    /** Every JetBrains configuration directory on this machine except the running IDE's own. */
    fun others(): List<Installation> {
        val own = runCatching { Paths.get(PathManager.getConfigPath()).normalize() }.getOrNull() ?: return emptyList()
        val parent = own.parent ?: return emptyList()
        return runCatching {
            Files.newDirectoryStream(parent).use { entries ->
                entries.filter { Files.isDirectory(it) && it.normalize() != own }
                    .map { Installation(it.fileName.toString(), it.toString()) }
            }
        }.getOrDefault(emptyList()).sortedBy { it.name }
    }

    /**
     * The project paths [installation] remembers, from its own `recentProjects.xml`.
     *
     * Read with a regular expression rather than an XML parser, and that is the safer choice rather than the
     * lazy one: the alternative is standing up a parser and remembering to disable external entities on a
     * file this code has no reason to interpret. All that is wanted is the keys of one map.
     *
     * A path whose directory no longer exists is dropped — the project is gone, so there is nothing to
     * migrate for it.
     */
    fun recentProjects(installation: Installation): List<String> {
        val file = Paths.get(installation.configPath, OPTIONS, RECENT_PROJECTS)
        val body = runCatching { Files.readString(file) }.getOrNull() ?: return emptyList()
        val block = body.substringAfter(ADDITIONAL_INFO, "").substringBefore(END_OPTION, "")
        return ENTRY_KEY.findAll(block)
            .map { expand(it.groupValues[1]) }
            .filter { it.isNotBlank() && runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) }
            .distinct()
            .sorted()
            .toList()
    }

    /** The IDE writes the user's home as a macro so the file survives being copied; undo that. */
    private fun expand(raw: String): String {
        val home = System.getProperty("user.home").orEmpty()
        return if (home.isBlank()) raw else raw.replace(USER_HOME, home)
    }

    private val ENTRY_KEY = Regex("""<entry key="([^"]+)"""")

    private const val OPTIONS = "options"
    private const val RECENT_PROJECTS = "recentProjects.xml"
    private const val ADDITIONAL_INFO = "\"additionalInfo\""
    private const val END_OPTION = "</option>"
    private const val USER_HOME = "\$USER_HOME\$"
}
