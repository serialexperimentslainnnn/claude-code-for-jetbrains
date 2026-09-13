package dev.lain.claudejb.controller.process

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
    fun `the tab creation API the launcher calls exists on this platform and is not deprecated`() {
        val manager = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
        val builder = manager.getMethod("createTabBuilder").returnType
        for (name in listOf("workingDirectory", "tabName")) {
            assertEquals(builder, builder.getMethod(name, String::class.java).returnType, "$name must chain")
        }
        for (name in listOf("requestFocus", "deferSessionStartUntilUiShown")) {
            assertEquals(builder, builder.getMethod(name, java.lang.Boolean.TYPE).returnType, "$name must chain")
        }
        val tab = builder.getMethod("createTab").returnType
        val view = tab.getMethod("getView").returnType
        val sendText = view.getMethod("createSendTextBuilder").returnType
        assertEquals(sendText, sendText.getMethod("shouldExecute").returnType, "shouldExecute must chain")
        assertEquals(Void.TYPE, sendText.getMethod("send", String::class.java).returnType)
        val deprecated = listOf(manager, builder, tab, view, sendText)
            .flatMap { it.methods.toList() }
            .filter { it.isAnnotationPresent(java.lang.Deprecated::class.java) }
            .map { it.name }
        assertEquals(emptyList<String>(), deprecated, "the launcher's terminal API must carry no deprecated method")
    }

    @Test
    fun `the launcher reaches the terminal without reflection`() {
        val source = sequenceOf(
            java.io.File("src/main/kotlin/dev/lain/claudejb/controller/process/TerminalLauncher.kt"),
            java.io.File("../src/main/kotlin/dev/lain/claudejb/controller/process/TerminalLauncher.kt"),
        ).first { it.isFile }.readText()

        assertEquals(0, Regex("""\bgetMethod\(""").findAll(source).count()) {
            "TerminalLauncher went back to reflection; the five-argument createNewSession is @ApiStatus.Internal"
        }
        assertTrue(
            source.contains("createTabBuilder()"),
            "TerminalLauncher must open its tab through TerminalToolWindowTabsManager; createShellWidget is deprecated since 261",
        )
        assertFalse(source.contains("createShellWidget("), "createShellWidget is deprecated since 261")
    }
}
