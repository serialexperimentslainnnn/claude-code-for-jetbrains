package dev.lain.claudejb.settings

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object OtherIdeConfigs {

    data class Installation(val name: String, val configPath: String)

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

    fun recentProjects(installation: Installation): List<String> {
        val file = Paths.get(installation.configPath, OPTIONS, RECENT_PROJECTS)
        val body = runCatching { Files.readString(file) }.getOrNull() ?: return emptyList()
        val block = body.substringAfter(ADDITIONAL_INFO, "").substringBefore(END_OPTION, "")
        return ENTRY_KEY.findAll(block)
            .map { withHomeExpanded(it.groupValues[1]) }
            .filter { it.isNotBlank() && stillOnDisk(it) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun stillOnDisk(path: String) = runCatching { Files.isDirectory(Path.of(path)) }.getOrDefault(false)

    private fun withHomeExpanded(raw: String): String {
        val home = System.getProperty("user.home").orEmpty()
        return if (home.isBlank()) raw else raw.replace(USER_HOME_MACRO, home)
    }

    private val ENTRY_KEY = Regex("""<entry key="([^"]+)"""")

    private const val OPTIONS = "options"
    private const val RECENT_PROJECTS = "recentProjects.xml"
    private const val ADDITIONAL_INFO = "\"additionalInfo\""
    private const val END_OPTION = "</option>"
    private const val USER_HOME_MACRO = "\$USER_HOME\$"
}
