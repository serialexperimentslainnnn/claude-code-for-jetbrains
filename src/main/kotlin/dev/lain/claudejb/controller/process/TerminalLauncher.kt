package dev.lain.claudejb.controller.process

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import dev.lain.claudejb.util.InstalledPlugins
import dev.lain.claudejb.util.thisLogger

object TerminalLauncher {

    private val log = thisLogger()
    private const val TERMINAL_PLUGIN_ID = "org.jetbrains.plugins.terminal"

    fun isAvailable(): Boolean = InstalledPlugins.isEnabled(TERMINAL_PLUGIN_ID)

    fun commandLine(
        binaryPath: String,
        args: List<String> = emptyList(),
        isWindows: Boolean = SystemInfo.isWindows,
    ): String {
        val quoted = (listOf("\"$binaryPath\"") + args).joinToString(" ")
        return if (isWindows) "& $quoted" else quoted
    }

    fun loginCommand(
        binaryPath: String,
        args: List<String> = listOf("auth", "login"),
        isWindows: Boolean = SystemInfo.isWindows,
    ): String = commandLine(binaryPath, args, isWindows)

    fun openAndRunCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        if (!isAvailable()) return false
        if (argv.isEmpty()) return false
        return runCatching { openWithShellCommand(project, argv, tabName) }
            .onFailure { log.warn("Failed to open IDE terminal for: $tabName", it) }
            .getOrDefault(false)
    }

    private fun openWithShellCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        val tab = TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .workingDirectory(project.basePath)
            .tabName(tabName)
            .requestFocus(true)
            .deferSessionStartUntilUiShown(false)
            .createTab()
        tab.view.createSendTextBuilder().shouldExecute().send(commandLine(argv.first(), argv.drop(1)))
        return true
    }
}
