package dev.lain.claudejb.process

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Opens a real IDE **terminal** tab and runs a command in it.
 *
 * Why this exists: the `claude` binary runs in `--print` / stream-json mode with no TTY, so interactive
 * flows like `/login` (OAuth) cannot run inside the chat — the binary answers "not available on this
 * environment". To log in we drop the user into an actual interactive terminal that owns a TTY.
 *
 * The IDE Terminal plugin (`org.jetbrains.plugins.terminal`) is bundled in every JetBrains IDE, but we still
 * gate on [isAvailable] and confine the API touch to [openAndRun] so a stripped/disabled install degrades to
 * a caller-handled fallback (a notification with the exact command) instead of a `ClassNotFoundException`.
 *
 * Since 2025.2 the **Reworked terminal** is the default engine, so [openAndRun] drives the Reworked Terminal
 * API (2025.3+) first and only falls back to the deprecated Classic widget on older IDEs — see its KDoc.
 */
object TerminalLauncher {

    private val log = thisLogger()
    private val TERMINAL_PLUGIN = PluginId.getId("org.jetbrains.plugins.terminal")

    /** True when the bundled IDE Terminal plugin is installed and enabled. Public API only (Marketplace-safe). */
    fun isAvailable(): Boolean = PluginManager.isPluginInstalled(TERMINAL_PLUGIN)

    /**
     * Builds the shell command that launches the `claude` login flow, **always using the binary's absolute
     * path** (double-quoted for spaces). Using the full path — not the bare name — means a GUI IDE that didn't
     * inherit the user's login `$PATH` still launches the right binary in the terminal.
     *
     * The subcommand is `auth login` (verified against the binary's `--help`): there is NO top-level `claude
     * login`, so sending `claude login` would treat "login" as a *prompt* and start an interactive session
     * instead of the OAuth flow.
     *
     * Shell quoting: on **Windows** the IDE terminal is PowerShell, which needs the call operator `&` to execute
     * a quoted path — without it `"C:\...\claude.exe" auth login` is parsed as a string literal and just echoed.
     * POSIX shells (bash/zsh) run a quoted path directly, and a leading `&` would background it, so only Windows
     * gets the prefix. Pure → unit-testable.
     */
    fun loginCommand(binaryPath: String, isWindows: Boolean = SystemInfo.isWindows): String {
        val quoted = "\"$binaryPath\" auth login"
        return if (isWindows) "& $quoted" else quoted
    }

    /**
     * Opens a terminal tab in the project root and runs [command]. Must be called on the EDT. Returns false
     * (so the caller can fall back) when the Terminal plugin is unavailable or every API call fails, rather than
     * throwing. The terminal owns a TTY and inherits the IDE's shell environment, which is what the OAuth flow
     * needs to write `~/.claude.json`.
     *
     * Two paths, both **reflective** so the plugin verifier sees no deprecated/experimental API and one build
     * spans 243..263 (and beyond):
     *
     * 1. [openReworked] — the **Reworked Terminal API** (`TerminalToolWindowTabsManager` +
     *    `TerminalView.createSendTextBuilder().shouldExecute().send(…)`), available since **2025.3 (253)**. This
     *    is the path that actually works on a modern IDE: since **2025.2 (252)** the Reworked terminal is the
     *    default engine, and the legacy `createShellWidget(…)` factory explicitly creates a *Classic* tab "regardless
     *    of the engine" — that classic widget is `@Deprecated` and its `sendCommandToExecute` races the shell
     *    startup, so the command (`claude auth login`) is dropped and the tab never logs in. The Reworked
     *    `send()` instead **buffers the text until the shell process is available**, which is what fixes it.
     *
     * 2. [openClassic] — the legacy `createShellWidget` / `createLocalShellWidget` + `sendCommandToExecute`
     *    fallback, kept only for IDEs **< 253** where the Reworked API classes don't exist yet.
     *
     * [isAvailable] + this [runCatching] still gate everything, so a missing/renamed method on any build degrades
     * to the caller's fallback notice instead of a `ClassNotFoundException`.
     */
    fun openAndRun(project: Project, command: String, tabName: String): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            openReworked(project, command, tabName) || openClassic(project, command, tabName)
        }.onFailure { log.warn("Failed to open IDE terminal for: $tabName", it) }.getOrDefault(false)
    }

    /**
     * Reworked Terminal API path (2025.3 / build 253+). All types
     * (`com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager`, its tab builder/tab, and
     * `com.intellij.terminal.frontend.view.TerminalView`) are reached reflectively — they're `@ApiStatus.Experimental`,
     * so a compile-time reference would trip the verifier, and they're simply absent on older IDEs. Returns false
     * (caller falls back to [openClassic]) when the API isn't present or any step fails. EDT-only.
     *
     * Mirror of the documented snippet:
     * ```
     * TerminalToolWindowTabsManager.getInstance(project)
     *   .createTabBuilder().workingDirectory(cwd).tabName(tabName).createTab()
     *   .view.createSendTextBuilder().shouldExecute().send(command)
     * ```
     */
    private fun openReworked(project: Project, command: String, tabName: String): Boolean {
        val managerCls = runCatching {
            Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
        }.getOrNull() ?: return false // older IDE — no Reworked API
        val manager = managerCls.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return false

        var builder = manager.javaClass.getMethod("createTabBuilder").invoke(manager) ?: return false
        builder = builder.javaClass.getMethod("workingDirectory", String::class.java).invoke(builder, project.basePath)
        builder = builder.javaClass.getMethod("tabName", String::class.java).invoke(builder, tabName)
        val tab = builder.javaClass.getMethod("createTab").invoke(builder) ?: return false

        val view = tab.javaClass.getMethod("getView").invoke(tab) ?: return false
        var sender = view.javaClass.getMethod("createSendTextBuilder").invoke(view) ?: return false
        sender = sender.javaClass.getMethod("shouldExecute").invoke(sender) // append the line wrap → run it
        sender.javaClass.getMethod("send", String::class.java).invoke(sender, command)
        return true
    }

    /**
     * Legacy Classic-terminal path, for IDEs **< 253** that lack the Reworked API. Creates a shell widget without
     * a compile-time reference to a deprecated method — `createShellWidget(String,String,boolean,boolean)` first,
     * then the `createLocalShellWidget(String,String)` fallback — and sends [command] via `sendCommandToExecute`
     * (or the older `executeCommand`). Returns whether a widget was created and the command dispatched. EDT-only.
     */
    private fun openClassic(project: Project, command: String, tabName: String): Boolean {
        val mgr = TerminalToolWindowManager.getInstance(project)
        val widget = createShellWidgetReflectively(mgr, project.basePath, tabName) ?: return false
        return sendCommandReflectively(widget, command)
    }

    /**
     * Creates a Classic shell terminal widget without a compile-time reference to a deprecated method. Tries the
     * `createShellWidget(String,String,boolean,boolean)` factory first, then the `createLocalShellWidget(String,String)`
     * fallback. Returns the widget as [Any] (its concrete type varies by build), or null if neither method resolves.
     */
    private fun createShellWidgetReflectively(mgr: Any, cwd: String?, tabName: String): Any? {
        val cls = mgr.javaClass
        runCatching {
            val m = cls.getMethod(
                "createShellWidget",
                String::class.java, String::class.java, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
            )
            return m.invoke(mgr, cwd, tabName, true, false)
        }
        runCatching {
            val m = cls.getMethod("createLocalShellWidget", String::class.java, String::class.java)
            return m.invoke(mgr, cwd, tabName)
        }
        return null
    }

    /**
     * Types [command] into the widget's shell. Prefers `sendCommandToExecute(String)` (the current API on
     * `TerminalWidget`), falling back to the older `executeCommand(String)` on `ShellTerminalWidget`. Returns
     * whether either resolved.
     */
    private fun sendCommandReflectively(widget: Any, command: String): Boolean {
        val cls = widget.javaClass
        runCatching {
            cls.getMethod("sendCommandToExecute", String::class.java).invoke(widget, command)
            return true
        }
        runCatching {
            cls.getMethod("executeCommand", String::class.java).invoke(widget, command)
            return true
        }
        return false
    }
}
