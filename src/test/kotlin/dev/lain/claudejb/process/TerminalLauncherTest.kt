package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalLauncherTest {

    @Test
    fun `posix command uses the quoted absolute path and the auth login subcommand`() {
        assertEquals(
            "\"/home/u/.local/bin/claude\" auth login",
            TerminalLauncher.loginCommand("/home/u/.local/bin/claude", isWindows = false),
        )
    }

    @Test
    fun `posix path with spaces stays quoted as a single token and is not backgrounded`() {
        val cmd = TerminalLauncher.loginCommand("/Applications/My Tools/claude", isWindows = false)
        assertEquals("\"/Applications/My Tools/claude\" auth login", cmd)
        assertFalse(cmd.startsWith("&"))
    }

    @Test
    fun `windows command is prefixed with the PowerShell call operator`() {
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

    @Test
    fun `the subcommand comes from the caller, so Console and SSO are not silently turned into a plain login`() {
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

    @Test
    fun `the launcher asks the platform for exactly the overload this test pins`() {
        val source = sequenceOf(
            java.io.File("src/main/kotlin/dev/lain/claudejb/process/TerminalLauncher.kt"),
            java.io.File("../src/main/kotlin/dev/lain/claudejb/process/TerminalLauncher.kt"),
        ).first { it.isFile }.readText()

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
