package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [TerminalLauncher.loginCommand]: the `claude login` command must always carry the binary's **absolute
 * path**, double-quoted, so a GUI IDE that didn't inherit the user's login `$PATH` still launches the right
 * binary (the whole reason we open a terminal instead of relying on the shell's PATH).
 */
class TerminalLauncherTest {

    @Test
    fun `posix command uses the quoted absolute path and the auth login subcommand`() {
        // There is no top-level `claude login` — it must be `auth login`, else "login" is read as a prompt.
        assertEquals(
            "\"/home/u/.local/bin/claude\" auth login",
            TerminalLauncher.loginCommand("/home/u/.local/bin/claude", isWindows = false),
        )
    }

    @Test
    fun `posix path with spaces stays quoted as a single token and is not backgrounded`() {
        val cmd = TerminalLauncher.loginCommand("/Applications/My Tools/claude", isWindows = false)
        assertEquals("\"/Applications/My Tools/claude\" auth login", cmd)
        // No leading '&' on POSIX — that would background the process instead of running it.
        assertFalse(cmd.startsWith("&"))
    }

    @Test
    fun `windows command is prefixed with the PowerShell call operator`() {
        // PowerShell needs `& "path"` to execute a quoted path; without it the string is just echoed.
        assertEquals(
            "& \"C:\\Users\\u\\scoop\\shims\\claude.exe\" auth login",
            TerminalLauncher.loginCommand("C:\\Users\\u\\scoop\\shims\\claude.exe", isWindows = true),
        )
    }

    @Test
    fun `windows path with spaces stays a single quoted token after the call operator`() {
        val cmd = TerminalLauncher.loginCommand("C:\\Program Files\\claude\\claude.exe", isWindows = true)
        assertEquals("& \"C:\\Program Files\\claude\\claude.exe\" auth login", cmd)
        assertTrue(cmd.startsWith("& \""))
    }

    // ── argv form: what actually gets handed to the terminal (no shell parses it) ────────────────────────────

    @Test
    fun `loginArgv is the binary plus the auth login subcommand, unquoted and unsplit`() {
        // Passed as the tab's shellCommand, so a path with spaces must stay ONE element — no quoting, no escaping,
        // no PowerShell call operator: there is no shell in the middle to misparse it.
        assertEquals(
            listOf("/Applications/My Tools/claude", "auth", "login"),
            TerminalLauncher.loginArgv("/Applications/My Tools/claude"),
        )
        assertEquals(
            listOf("C:\\Program Files\\claude\\claude.exe", "auth", "login"),
            TerminalLauncher.loginArgv("C:\\Program Files\\claude\\claude.exe"),
        )
    }
}

/**
 * REGRESSION (4.4.1): `/login` always fell through to "run this yourself in a terminal" on a current IDE, because
 * every terminal API the launcher reflected on was missing at runtime:
 *  - `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` — not present in the shipped IDE
 *    (verified by scanning every jar of IU-262.8665.337);
 *  - `TerminalToolWindowManager.createShellWidget(…)` / `.createLocalShellWidget(…)` — present on 251/252 but
 *    **removed by 262**.
 * Every lookup returns false instead of throwing, so the breakage was silent — nothing in the log, just a dead end.
 *
 * **Why CI never caught it, and why this test is shaped the way it is.** The plugin compiles and tests against
 * IC-2025.2 (252), where the removed factories still exist — so any test asserting "those methods are gone" would
 * PASS on the test classpath and tell us nothing about the user's 262 runtime. That asymmetry *is* the bug's hiding
 * place. So this pins the only thing that actually protects us: that the replacement overload
 * (`createNewSession`, verified by hand on 251, 252 AND 262) exists on whatever platform we build against. If it
 * ever disappears the way its predecessors did, this fails at build time instead of a user hitting the dead end.
 * The `verifyPlugin` run across the declared range is the complementary half of this guard.
 */
class TerminalApiContractTest {

    @Test
    fun `the createNewSession overload the launcher reflects on exists on this platform`() {
        val cls = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val m = cls.getMethod(
            "createNewSession",
            String::class.java,
            String::class.java,
            List::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
        )
        assertTrue(m.returnType != Void.TYPE, "createNewSession must return a widget we can null-check")
    }
}
