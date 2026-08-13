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
 * REGRESSION (4.4.1): `/login` always fell through to "run this yourself in a terminal", because both terminal
 * paths the launcher reflected on returned false at runtime — and every lookup returns false instead of throwing,
 * so the breakage was silent: nothing in the log, just a dead end.
 *
 * **Do not repeat the diagnosis that was written down for it.** The commit message and this file used to say the
 * reflected types were "missing from the shipped IDE" and "removed by 262". Re-checked with `javap` against the
 * distributions themselves, that is false in both halves:
 * `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` is in `plugins/terminal/lib/terminal.jar`
 * on IC-253.28294.334 and in `plugins/terminal/lib/modules/intellij.terminal.frontend.jar` on PY-262.9437.71, and
 * `TerminalToolWindowManager.createShellWidget(…)` / `.createLocalShellWidget(…)` are present on 251, 252, 253 AND
 * 262. Whatever made those paths fail, it was not an absent symbol — which is exactly why this test does not try
 * to assert an absence.
 *
 * **What it pins instead**, because it is the only thing that actually protects the feature: that the replacement
 * overload `createNewSession(String, String, List, boolean, boolean)` exists on whatever platform we build
 * against. It is verified present on IC-251.29188.72, IC-252.28539.97, IC-253.28294.334 and PY-262.9437.71, i.e.
 * the whole declared range in both product families; if it ever disappears, this fails at build time instead of a
 * user hitting the dead end. Since 5.5.0 it is also the ONLY in-class path, so a failure here is a dead `/login`,
 * not a downgrade. The `verifyPlugin` run across the declared range is the complementary half of this guard.
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

    /**
     * The half the platform check cannot cover: that the signature pinned above is the signature
     * [TerminalLauncher] actually asks for.
     *
     * Both are hand-written reflective lookups, so they can drift apart silently — and the drift would be
     * invisible in exactly the same way the 4.4.1 regression was: the guard stays green while `/login` dies,
     * because `getMethod` returns null-into-false rather than throwing. Cheap to pin, and the only way this
     * test means what its name says.
     */
    @Test
    fun `the launcher asks the platform for exactly the overload this test pins`() {
        val source = sequenceOf(
            java.io.File("src/main/kotlin/dev/lain/claudejb/process/TerminalLauncher.kt"),
            java.io.File("../src/main/kotlin/dev/lain/claudejb/process/TerminalLauncher.kt"),
        ).first { it.isFile }.readText()

        // Whitespace-insensitive: the argument list, not its formatting.
        val call = source.substringAfter("getMethod(").substringBefore(")").filterNot { it.isWhitespace() }
        assertEquals(
            "\"createNewSession\",String::class.java,String::class.java,List::class.java," +
                "java.lang.Boolean.TYPE,java.lang.Boolean.TYPE,",
            call,
            "TerminalLauncher reflects on a different signature than TerminalApiContractTest verifies",
        )
        assertEquals(1, Regex("""\bgetMethod\(""").findAll(source).count()) {
            "more than one reflective lookup in TerminalLauncher — this contract only covers the first"
        }
    }
}
