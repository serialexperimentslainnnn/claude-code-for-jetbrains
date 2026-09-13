package dev.lain.claudejb.controller.github

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GitHubGatewayContractTest {

    private val sources: List<File> = MainSources.files()
    private val descriptor = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `the gateway exists and is the only file naming a GitHub plugin type`() {
        val importers = sources
            .filter { file -> MainSources.codeOf(file).any { GITHUB_TYPE.containsMatchIn(it) } }
            .map { it.name }
            .sorted()
        assertEquals(listOf(GATEWAY), importers) {
            "org.jetbrains.plugins.github may be named by $GATEWAY only: with the GitHub plugin disabled those classes are " +
                "not on our classpath, and any other file naming them dies with a LinkageError when first touched."
        }
    }

    @Test
    fun `every entry of the gateway checks the plugin before touching it`() {
        val code = MainSources.codeOf(sources.single { it.name == GATEWAY })
        val entries = code.withIndex().filter { (_, line) -> ENTRY.matches(line) }
        assertTrue(entries.isNotEmpty()) { "$GATEWAY has no public entry point; the pattern has stopped matching" }
        val unguarded = entries.filter { (index, _) -> code[index + 1].trim() != "$GUARD()" }.map { it.value.trim() }
        assertEquals(emptyList<String>(), unguarded) { "every non-private function of $GATEWAY starts with $GUARD()" }
    }

    @Test
    fun `the GitHub plugin is an optional dependency with an existing config-file, and the build compiles against it`() {
        val match = DEPENDS.find(descriptor)
        assertTrue(match != null, "No <depends…>org.jetbrains.plugins.github</depends> in plugin.xml")
        assertTrue("optional=\"true\"" in match!!.value, "the GitHub dependency must be optional: ${match.value}")
        val configFile = Regex("""config-file="([^"]+)"""").find(match.value)?.groupValues?.get(1)
        assertTrue(configFile != null && File("src/main/resources/META-INF/$configFile").isFile, "config-file $configFile is missing")
        assertTrue(Regex("""bundledPlugin\(\s*"org\.jetbrains\.plugins\.github"\s*\)""").containsMatchIn(File("build.gradle.kts").readText()))
    }

    private companion object {
        const val GATEWAY = "GitHubGateway.kt"
        const val GUARD = "requireGitHub"

        val GITHUB_TYPE = Regex("""\borg\.jetbrains\.plugins\.github\.[A-Za-z]""")
        val ENTRY = Regex("""^ {4}(suspend )?fun \w+\(.*""")
        val DEPENDS = Regex("""<depends[^>]*>org\.jetbrains\.plugins\.github</depends>""")
    }
}
