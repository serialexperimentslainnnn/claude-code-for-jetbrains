package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.lain.claudejb.util.InstalledPlugins
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Opens a real IDE **terminal** tab and runs a command in it.
 *
 * Why this exists: the `claude` binary runs in `--print` / stream-json mode with no TTY, so interactive
 * flows like `/login` (OAuth) cannot run inside the chat — the binary answers "not available on this
 * environment". To log in we drop the user into an actual interactive terminal that owns a TTY.
 *
 * The IDE Terminal plugin (`org.jetbrains.plugins.terminal`) is bundled in every JetBrains IDE, but we still
 * gate on [isAvailable] and confine the API touch to [openAndRunCommand] so a stripped/disabled install degrades
 * to a caller-handled fallback (the native PTY flow, then a notice) instead of a `ClassNotFoundException`.
 *
 * Every platform call is **reflective**: these are internal/experimental terminal APIs that have already broken
 * once across the plugin's declared range (see [openAndRunCommand] for the regression), so a rename must degrade
 * to a fallback rather than throw `NoSuchMethodError` at a user.
 */
object TerminalLauncher {

    private val log = thisLogger()
    private const val TERMINAL_PLUGIN_ID = "org.jetbrains.plugins.terminal"

    /** True when the bundled IDE Terminal plugin is installed and enabled. Public API only (Marketplace-safe). */
    fun isAvailable(): Boolean = InstalledPlugins.isEnabled(TERMINAL_PLUGIN_ID)

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
     * The login flow as an **argv list** — the form [openAndRunCommand] hands straight to the terminal as the tab's
     * process. Passing argv instead of a shell string removes the entire quoting problem class ([loginCommand]'s
     * PowerShell `&` prefix, paths with spaces) and the shell-startup race, because no shell parses it. Pure.
     */
    fun loginArgv(binaryPath: String): List<String> = listOf(binaryPath, "auth", "login")

    /**
     * Opens a terminal tab in the project root that **runs [argv] as the tab's own process**. Must be called on
     * the EDT. Returns false (so the caller can fall back) when the Terminal plugin is unavailable or every API
     * call fails, rather than throwing. The tab owns a TTY and inherits the IDE's environment, which is what the
     * OAuth flow needs to write `~/.claude.json`.
     *
     * **This is the fix for a real regression.** The previous implementation tried two reflective paths and, on a
     * current IDE, BOTH silently failed — so `/login` always degraded to "run this yourself in a terminal":
     *  - the Reworked path looked up `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager`,
     *    a class that **does not exist** in the shipped IDE (verified by scanning every jar of IU-262.8665.337);
     *  - the Classic path called `createShellWidget(…)` / `createLocalShellWidget(…)`, both of which existed on
     *    251/252 but were **removed by 262**, so the reflective lookup returned null.
     * The failure was invisible: each step returns false rather than throwing, so nothing reached the log.
     *
     * `createNewSession(workingDirectory, tabName, shellCommand, requestFocus, deferSessionStartUntilUiShown)` is
     * verified present on **251, 252 and 262 alike** — one call that spans the whole declared range. It is reached
     * reflectively anyway (its `TerminalWidget` return type is stable, but reflection keeps the verifier clear of
     * any deprecation churn and means a future rename degrades to the fallback instead of a `NoSuchMethodError`).
     *
     * Passing [argv] as the tab's `shellCommand` also removes two bug classes the old string-command path had:
     * no shell parses it (so no quoting hazard — see [loginArgv] vs [loginCommand]), and there is no
     * send-text-into-a-shell race to lose the command to.
     */
    fun openAndRunCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        if (!isAvailable()) return false
        if (argv.isEmpty()) return false
        return runCatching {
            openWithShellCommand(project, argv, tabName) ||
                openReworked(project, argv.joinToString(" "), tabName) ||
                openClassic(project, argv.joinToString(" "), tabName)
        }.onFailure { log.warn("Failed to open IDE terminal for: $tabName", it) }.getOrDefault(false)
    }

    /**
     * The path that actually works across 251→262+: `TerminalToolWindowManager.createNewSession(…)` with [argv] as
     * the tab's `shellCommand`, so the terminal runs the command directly instead of typing it into a shell.
     * `requestFocus = true` (the user must interact with the login), `deferSessionStartUntilUiShown = false` (start
     * immediately — there is nothing to defer for). EDT-only; returns false if the method isn't there.
     */
    private fun openWithShellCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        val mgr = TerminalToolWindowManager.getInstance(project)
        val method = runCatching {
            mgr.javaClass.getMethod(
                "createNewSession",
                String::class.java, String::class.java, List::class.java,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
            )
        }.getOrNull() ?: return false
        return runCatching {
            method.invoke(mgr, project.basePath, tabName, argv, true, false) != null
        }.getOrDefault(false)
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
