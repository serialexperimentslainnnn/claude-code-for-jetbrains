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

    // ── the sign-in mode travels with the command ───────────────────────────────────────────────────────────

    @Test
    fun `the subcommand comes from the caller, so Console and SSO are not silently turned into a plain login`() {
        // A last-resort notice that told a Console user to run the SUBSCRIPTION login would send them through
        // the wrong OAuth consent — a different account type, not a cosmetic difference.
        assertEquals(
            "\"/usr/bin/claude\" auth login --console",
            TerminalLauncher.loginCommand("/usr/bin/claude", listOf("auth", "login", "--console"), isWindows = false),
        )
        assertEquals(
            "& \"C:\\bin\\claude.exe\" auth login --sso",
            TerminalLauncher.loginCommand("C:\\bin\\claude.exe", listOf("auth", "login", "--sso"), isWindows = true),
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
