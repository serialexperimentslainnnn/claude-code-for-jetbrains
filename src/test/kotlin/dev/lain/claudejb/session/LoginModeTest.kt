package dev.lain.claudejb.session

import dev.lain.claudejb.process.TerminalLauncher
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginModeTest {

    @Test
    fun `each mode carries the auth login subcommand plus its own flag`() {
        assertEquals(listOf("auth", "login"), LoginCoordinator.Mode.SUBSCRIPTION.args)
        assertEquals(listOf("auth", "login", "--console"), LoginCoordinator.Mode.CONSOLE.args)
        assertEquals(listOf("auth", "login", "--sso"), LoginCoordinator.Mode.SSO.args)
    }

    @Test
    fun `no mode uses a bare login, which the binary would treat as a prompt`() {
        LoginCoordinator.Mode.entries.forEach { mode ->
            assertEquals("${mode.name} must go through the auth subcommand", "auth", mode.args.first())
            assertEquals("${mode.name} must call login", "login", mode.args[1])
        }
    }

    @Test
    fun `the manual fallback command quotes the binary and keeps the mode's flags`() {
        val posix = TerminalLauncher.loginCommand(
            "/home/u/my tools/claude",
            LoginCoordinator.Mode.CONSOLE.args,
            isWindows = false,
        )
        assertEquals("\"/home/u/my tools/claude\" auth login --console", posix)

        val windows = TerminalLauncher.loginCommand(
            "C:\\Program Files\\claude.exe",
            LoginCoordinator.Mode.SSO.args,
            isWindows = true,
        )
        assertEquals("& \"C:\\Program Files\\claude.exe\" auth login --sso", windows)
    }
}
