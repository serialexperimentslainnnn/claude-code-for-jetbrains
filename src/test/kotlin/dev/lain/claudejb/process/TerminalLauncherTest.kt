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
}
