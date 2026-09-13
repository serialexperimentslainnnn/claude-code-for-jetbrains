package dev.lain.claudejb.controller.mcp.tools.vcs

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GitCommandsGatewayContractTest {

    private val sources: List<File> = MainSources.files()

    private val gateway: File = sources.single { it.name == GATEWAY }

    @Test
    fun `the scan sees the gateway and the packages it pins`() {
        assertTrue(sources.any { it.path.replace('\\', '/').endsWith(READ_GATEWAY) }, "$READ_GATEWAY is missing")
        assertTrue(sources.any { it.path.replace('\\', '/').contains(MCP_PACKAGE) }, "no source under $MCP_PACKAGE")
    }

    @Test
    fun `every git4idea command symbol lives in the write gateway and nowhere else`() {
        val offenders = sources.filter { it.name != GATEWAY }.flatMap { file ->
            MainSources.codeOf(file).flatMap { line -> (COMMAND_SYMBOLS + BANNED_SYMBOLS).filter { it in line }.map { "${file.name}: $it" } }
        }
        assertEquals(emptyList<String>(), offenders) {
            "Git4Idea's command machinery is named outside $GATEWAY. Every git write goes through that one file, so the " +
                "availability check, the error mapping and the refresh happen once; a second entry point is a second place " +
                "for them to be forgotten."
        }
        val named = MainSources.codeOf(gateway).joinToString("\n")
        assertTrue(COMMAND_SYMBOLS.all { it in named }) {
            "$GATEWAY no longer names every pinned command symbol; the pin has drifted from the code: ${COMMAND_SYMBOLS.filterNot { it in named }}"
        }
    }

    @Test
    fun `git4idea is imported by the two gateways only`() {
        val importers = sources
            .filter { file -> MainSources.codeOf(file).any { it.trim().startsWith("import git4idea") } }
            .map { it.path.replace('\\', '/').substringAfter("dev/lain/claudejb/") }
            .sorted()
        assertEquals(listOf(READ_GATEWAY, WRITE_GATEWAY), importers) {
            "git4idea may be imported by the read gateway and the write gateway only: with the Git plugin disabled those " +
                "classes are not on the classpath, and any other importer dies with a LinkageError when it is first touched."
        }
    }

    @Test
    fun `every entry of the write gateway checks the Git plugin before touching git4idea`() {
        val code = MainSources.codeOf(gateway)
        val guard = code.indexOfFirst { GUARD.matches(it.trim()) }
        assertTrue(guard >= 0) { "$GATEWAY has no $GUARD_NAME function" }
        assertTrue(code[guard + 1].contains("GitAvailability.isGitPluginEnabled()")) {
            "$GUARD_NAME must name GitAvailability.isGitPluginEnabled() on its first line"
        }
        val entries = code.withIndex().filter { (_, line) -> ENTRY.matches(line) }
        assertTrue(entries.isNotEmpty()) { "$GATEWAY has no public entry point; the pattern has stopped matching" }
        val unguarded = entries.filter { (index, _) -> code[index + 1].trim() != "$GUARD_NAME()" }.map { it.value.trim() }
        assertEquals(emptyList<String>(), unguarded) {
            "Every non-private function of $GATEWAY starts with $GUARD_NAME(): the plugin can be disabled between two calls."
        }
    }

    @Test
    fun `nothing under controller mcp spawns a process, except the shell that runs in the IDE's terminal`() {
        val offenders = sources
            .filter { it.path.replace('\\', '/').contains(MCP_PACKAGE) && it.name != TERMINAL }
            .flatMap { file -> MainSources.codeOf(file).flatMap { line -> SPAWN_SYMBOLS.filter { it in line }.map { "${file.name}: $it" } } }
        assertEquals(emptyList<String>(), offenders) {
            "An MCP tool runs git, or anything else, as a child process. The tools exist because they use the IDE; a " +
                "spawned process is what Bash already does, and it bypasses the IDE's credentials, refresh and undo. " +
                "The one exception is $TERMINAL, whose process lives in the IDE's Terminal tool window with its console."
        }
    }

    private companion object {

        const val GATEWAY = "GitCommands.kt"
        const val READ_GATEWAY = "controller/git/GitGateway.kt"
        const val WRITE_GATEWAY = "controller/mcp/tools/vcs/GitCommands.kt"
        const val MCP_PACKAGE = "dev/lain/claudejb/controller/mcp/"
        const val TERMINAL = "TerminalTools.kt"
        const val GUARD_NAME = "requireGit"

        val GUARD = Regex("""private fun $GUARD_NAME\(\) \{""")
        val ENTRY = Regex("""^ {4}fun \w+\(.*""")

        val COMMAND_SYMBOLS = listOf(
            "GitLineHandler",
            "GitCommand.",
            "Git.getInstance",
            "GitFileUtils",
            "GitCheckinEnvironment",
            "GitBrancher",
        )

        val BANNED_SYMBOLS = listOf("GitFetchSupport", "GitImpl")

        val SPAWN_SYMBOLS = listOf("ProcessBuilder", "GeneralCommandLine", "Runtime.getRuntime")
    }
}
