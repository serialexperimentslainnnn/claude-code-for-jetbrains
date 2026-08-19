package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.util.InstalledPlugins
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

object TerminalLauncher {

    private val log = thisLogger()
    private const val TERMINAL_PLUGIN_ID = "org.jetbrains.plugins.terminal"

    fun isAvailable(): Boolean = InstalledPlugins.isEnabled(TERMINAL_PLUGIN_ID)

    fun loginCommand(
        binaryPath: String,
        args: List<String> = listOf("auth", "login"),
        isWindows: Boolean = SystemInfo.isWindows,
    ): String {
        val quoted = (listOf("\"$binaryPath\"") + args).joinToString(" ")
        return if (isWindows) "& $quoted" else quoted
    }

    fun openAndRunCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        if (!isAvailable()) return false
        if (argv.isEmpty()) return false
        return runCatching { openWithShellCommand(project, argv, tabName) }
            .onFailure { log.warn("Failed to open IDE terminal for: $tabName", it) }
            .getOrDefault(false)
    }

    private fun openWithShellCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        val mgr = TerminalToolWindowManager.getInstance(project)
        val method = runCatching {
            mgr.javaClass.getMethod(
                "createNewSession",
                String::class.java,
                String::class.java,
                List::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
            )
        }.getOrNull() ?: return false
        return runCatching {
            method.invoke(mgr, project.basePath, tabName, argv, true, false) != null
        }.getOrDefault(false)
    }
}
