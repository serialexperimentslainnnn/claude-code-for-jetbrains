package dev.lain.claudejb.controller.db

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DbGatewayContractTest {

    private val sources: List<File> = MainSources.files()

    private val gateway: File = sources.single { it.name == GATEWAY }

    @Test
    fun `the scan sees the gateway and the tools that call it`() {
        assertTrue(gateway.path.replace('\\', '/').endsWith(GATEWAY_PATH)) { "$GATEWAY moved out of $GATEWAY_PATH" }
        assertTrue(sources.any { it.path.replace('\\', '/').endsWith(TOOLS_PATH) }) { "$TOOLS_PATH is missing" }
    }

    @Test
    fun `the database plugin's package is named in the gateway and nowhere else`() {
        val elsewhere = sources.filter { it.name != GATEWAY && PACKAGE in it.readText() }.map { it.name }
        assertEquals(emptyList<String>(), elsewhere) {
            "$PACKAGE is named outside $GATEWAY. The Database plugin has no API contract: its classes move between " +
                "releases and are absent from Community IDEs, so every reference is a string resolved by reflection " +
                "in that one file, where a changed signature degrades to an error instead of a LinkageError at startup."
        }
        assertTrue(PACKAGE in gateway.readText()) { "$GATEWAY no longer names $PACKAGE; the pin has drifted from the code" }
    }

    @Test
    fun `nothing in the main sources imports the database plugin`() {
        val importers = sources.filter { file -> MainSources.codeOf(file).any { it.trim().startsWith("import $PACKAGE") } }.map { it.name }
        assertEquals(emptyList<String>(), importers) {
            "An import of $PACKAGE binds the class at load time; with the plugin missing the importer dies when first touched."
        }
    }

    @Test
    fun `every entry of the gateway checks the plugin is loaded before reflecting into it`() {
        val code = MainSources.codeOf(gateway)
        val guard = code.indexOfFirst { GUARD.matches(it.trim()) }
        assertTrue(guard >= 0) { "$GATEWAY has no $GUARD_NAME function" }
        assertTrue(code[guard + 1].contains("$AVAILABLE()")) { "$GUARD_NAME must name $AVAILABLE() on its first line" }
        val available = code.single { AVAILABLE_DECLARATION.containsMatchIn(it) }
        assertTrue("PluginManagerCore.isLoaded(" in available) { "$AVAILABLE must ask PluginManagerCore.isLoaded, the loaded-plugin check" }
        val entries = code.withIndex().filter { (_, line) -> ENTRY.matches(line) }
        assertTrue(entries.isNotEmpty()) { "$GATEWAY has no public entry point; the pattern has stopped matching" }
        val unguarded = entries.filter { (index, _) -> code[index + 1].trim() != "$GUARD_NAME()" }.map { it.value.trim() }
        assertEquals(emptyList<String>(), unguarded) {
            "Every non-private function of $GATEWAY starts with $GUARD_NAME(): the plugin can be disabled between two calls."
        }
    }

    @Test
    fun `every reflective step in the gateway is caught, so a moved class degrades instead of crashing`() {
        val code = MainSources.codeOf(gateway)
        val loads = code.filter { "loadClass(" in it }
        assertTrue(loads.isNotEmpty()) { "$GATEWAY no longer loads classes by name; the reflection seam has moved" }
        assertTrue(loads.all { "runCatching" in it }) { "loadClass outside runCatching: $loads" }
        val invokes = code.withIndex().filter { (_, line) -> ".invoke(" in line }
        assertTrue(invokes.isNotEmpty()) { "$GATEWAY no longer invokes by reflection; the seam has moved" }
        invokes.forEach { (index, _) ->
            assertTrue(code.subList(maxOf(0, index - TRY_WINDOW), index).any { "try {" in it }) {
                "an invoke at $GATEWAY:${index + 1} is not inside a try that maps InvocationTargetException to a ToolException"
            }
        }
    }

    private companion object {

        const val GATEWAY = "DbGateway.kt"
        const val GATEWAY_PATH = "controller/db/DbGateway.kt"
        const val TOOLS_PATH = "controller/mcp/tools/ops/DbTools.kt"
        const val PACKAGE = "com.intellij.database"
        const val GUARD_NAME = "requireDatabase"
        const val AVAILABLE = "isAvailable"
        const val TRY_WINDOW = 3

        val GUARD = Regex("""private fun $GUARD_NAME\(\) \{""")
        val AVAILABLE_DECLARATION = Regex("""fun $AVAILABLE\(\): Boolean""")
        val ENTRY = Regex("""^ {4}fun \w+\(.*""")
    }
}
