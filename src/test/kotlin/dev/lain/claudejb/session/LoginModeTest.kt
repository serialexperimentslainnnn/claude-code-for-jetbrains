package dev.lain.claudejb.session

import dev.lain.claudejb.process.TerminalLauncher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sign-in routes are argv, and the argv IS the route: `--console` bills API usage against an organization
 * and its consent carries `org:create_api_key`, while the plain form signs in to a personal subscription. A
 * silent mix-up here signs the user into the wrong thing and looks like a working login, so the flags are
 * pinned rather than trusted — including through [TerminalLauncher.loginCommand], the last-resort text the
 * user is told to run by hand, which must name the same subcommand the card would have run.
 */
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

        // PowerShell needs the call operator to execute a quoted path; POSIX must NOT get it (it would background).
        val windows = TerminalLauncher.loginCommand(
            "C:\\Program Files\\claude.exe",
            LoginCoordinator.Mode.SSO.args,
            isWindows = true,
        )
        assertEquals("& \"C:\\Program Files\\claude.exe\" auth login --sso", windows)
    }
}
