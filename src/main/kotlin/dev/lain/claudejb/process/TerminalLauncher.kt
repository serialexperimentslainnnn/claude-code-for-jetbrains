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
 * The platform call is **reflective**: this is an internal terminal API that has already broken once across the
 * plugin's declared range (see [openAndRunCommand] for the regression), so a rename must degrade to the caller's
 * fallback rather than throw `NoSuchMethodError` at a user.
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
     * [args] is the sign-in's own subcommand, supplied by the caller (`auth login`, plus `--console`/`--sso`
     * for the Console and SSO routes — see `LoginCoordinator.Mode`). It defaults to the plain subscription
     * login. Note there is NO top-level `claude login` (verified against the binary's `--help`): sending that
     * would treat "login" as a *prompt* and start an interactive session instead of the OAuth flow.
     *
     * Shell quoting: on **Windows** the IDE terminal is PowerShell, which needs the call operator `&` to execute
     * a quoted path — without it `"C:\...\claude.exe" auth login` is parsed as a string literal and just echoed.
     * POSIX shells (bash/zsh) run a quoted path directly, and a leading `&` would background it, so only Windows
     * gets the prefix. Pure → unit-testable.
     *
     * This is the LAST-RESORT form: it is text for the user to run by hand. The terminal tab itself is handed
     * an argv list ([openAndRunCommand]), which has no quoting problem class at all.
     */
    fun loginCommand(
        binaryPath: String,
        args: List<String> = listOf("auth", "login"),
        isWindows: Boolean = SystemInfo.isWindows,
    ): String {
        val quoted = (listOf("\"$binaryPath\"") + args).joinToString(" ")
        return if (isWindows) "& $quoted" else quoted
    }

    /**
     * Opens a terminal tab in the project root that **runs [argv] as the tab's own process**. Must be called on
     * the EDT. Returns false (so the caller can fall back) when the Terminal plugin is unavailable or every API
     * call fails, rather than throwing. The tab owns a TTY and inherits the IDE's environment, which is what the
     * OAuth flow needs to write `~/.claude.json`.
     *
     * **This is the fix for a real regression.** Before 4.4.1 `/login` always degraded to "run this yourself in
     * a terminal", because the two reflective paths it tried both returned false on the IDE of the day — and
     * each step returns false rather than throwing, so nothing reached the log.
     *
     * `createNewSession(workingDirectory, tabName, shellCommand, requestFocus, deferSessionStartUntilUiShown)` is
     * the call that works, and it is not a guess: `javap` on `plugins/terminal/lib/terminal.jar` reports it
     * present on IC-251.29188.72, IC-252.28539.97, IC-253.28294.334 **and** PY-262.9437.71 — the whole declared
     * range and below it, IDEA and PyCharm alike. It is reached reflectively anyway (its `TerminalWidget` return
     * type is stable, but reflection keeps the verifier clear of any deprecation churn and means a future rename
     * degrades to the caller's fallback instead of a `NoSuchMethodError` at a user). [TerminalApiContractTest]
     * pins it against the build classpath.
     *
     * Passing [argv] as the tab's `shellCommand` also removes two bug classes the old string-command path had:
     * no shell parses it (so no quoting hazard — contrast [loginCommand], which must quote), and there is no
     * send-text-into-a-shell race to lose the command to.
     *
     * **The two chained fallbacks were removed in 5.5.0, and NOT because their APIs are gone.** That claim was
     * checked and it is false: `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` ships in
     * `plugins/terminal/lib/terminal.jar` on 253 and in `plugins/terminal/lib/modules/intellij.terminal.frontend.jar`
     * on 262, and `createShellWidget(…)` / `createLocalShellWidget(…)` are still on `TerminalToolWindowManager` in
     * 251, 252, 253 and 262. They went for two reasons that do hold:
     *  1. **Unreachable.** The chain only advanced when the primary failed to resolve, and the primary resolves on
     *     every build in range (above).
     *  2. **Worse than nothing if it ever did run.** Both took `argv.joinToString(" ")` — a shell STRING, which is
     *     precisely the quoting hazard the argv path exists to remove (a path with spaces, the Windows `&`
     *     prefix), and `openClassic` additionally typed it into a live shell, reintroducing the send race. A
     *     fallback that silently runs a *mis-quoted* command is not a safety net.
     * The real fallback lives outside this class, where a false answer here is handled: `LoginCoordinator` goes
     * terminal → native PTY → manual notice.
     */
    fun openAndRunCommand(project: Project, argv: List<String>, tabName: String): Boolean {
        if (!isAvailable()) return false
        if (argv.isEmpty()) return false
        return runCatching { openWithShellCommand(project, argv, tabName) }
            .onFailure { log.warn("Failed to open IDE terminal for: $tabName", it) }
            .getOrDefault(false)
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
