package dev.lain.claudejb.process

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.io.File

object ClaudeBinaryLocator {

    private val home: String get() = System.getProperty("user.home").orEmpty()

    private val SHIM_ENTRYPOINT = Regex("%~dp0[\\\\/]?([^\"\\s]+\\.js)")

    internal val executableNames: List<String>
        get() = if (SystemInfo.isWindows) {
            listOf("claude.exe", "claude.cmd", "claude.bat")
        } else {
            listOf("claude")
        }

    private val typicalDirs: List<String>
        get() = if (SystemInfo.isWindows) {
            listOfNotNull(
                "$home\\.local\\bin",
                System.getenv("APPDATA")?.let { "$it\\npm" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\claude" },
                "$home\\scoop\\shims",
                "$home\\.volta\\bin",
                System.getenv("ChocolateyInstall")?.let { "$it\\bin" },
            )
        } else {
            listOf(
                "$home/.local/bin",
                "$home/.claude/local",
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "/usr/bin",
            )
        }

    fun locate(override: String? = null): File? {
        override?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf { it.isFile && it.canExecute() }?.let { return it }
        }
        for (name in executableNames) {
            PathEnvironmentVariableUtil.findInPath(name)?.let { if (it.canExecute()) return it }
        }
        for (dir in typicalDirs) {
            for (name in executableNames) {
                val candidate = File(dir, name)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        return null
    }

    fun resolveNodeScript(binary: File): File? {
        if (!SystemInfo.isWindows) return null
        val name = binary.name.lowercase()
        if (!name.endsWith(".cmd") && !name.endsWith(".bat")) return null
        val dir = binary.parentFile ?: return null
        File(dir, "node_modules\\@anthropic-ai\\claude-code\\cli.js").takeIf { it.isFile }?.let { return it }
        return runCatching {
            SHIM_ENTRYPOINT.find(binary.readText())
                ?.groupValues?.get(1)
                ?.let { File(dir, it) }
                ?.takeIf { it.isFile }
        }.getOrNull()
    }

    fun locateNode(near: File?, override: String? = null): String {
        override?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf { it.isFile && it.canExecute() }?.let { return it.absolutePath }
        }
        if (!SystemInfo.isWindows) return "node"
        near?.let { File(it.parentFile, "node.exe") }?.takeIf { it.isFile }?.let { return it.absolutePath }
        PathEnvironmentVariableUtil.findInPath("node.exe")?.let { if (it.canExecute()) return it.absolutePath }
        System.getenv("ProgramFiles")?.let { File("$it\\nodejs\\node.exe") }?.takeIf { it.isFile }
            ?.let { return it.absolutePath }
        return "node"
    }
}
