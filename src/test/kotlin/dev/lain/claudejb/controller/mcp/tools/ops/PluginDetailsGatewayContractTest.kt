package dev.lain.claudejb.controller.mcp.tools.ops

import dev.lain.claudejb.MainSources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PluginDetailsGatewayContractTest {

    private val sources: List<File> = MainSources.files()

    private val gateway: File = sources.single { it.name == GATEWAY }

    @Test
    fun `the plugin details service is reached by name in the gateway and nowhere else`() {
        assertTrue(SERVICE in gateway.readText()) { "$GATEWAY no longer names $SERVICE; the pin has drifted from the code" }
        val elsewhere = sources.filter { it.name != GATEWAY && SERVICE_CLASS in it.readText() }.map { it.name }
        assertEquals(emptyList<String>(), elsewhere) {
            "$SERVICE_CLASS is named outside $GATEWAY. It arrived in 2026.2 and the floor is 253, so it is reached by " +
                "reflection in that one file, where an older IDE degrades to a ToolException instead of a LinkageError."
        }
    }

    @Test
    fun `no main source lists plugins through the plugin manager, which is internal since 262`() {
        val offenders = sources.filter { file ->
            MainSources.codeOf(file).any { line -> LISTING.any { it in line } }
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders) { "PluginManagerCore's plugin arrays are @ApiStatus.Internal since 262" }
    }

    @Test
    fun `the service lookup is caught, so an IDE without it answers not available`() {
        val code = MainSources.codeOf(gateway)
        val lookup = code.single { "Class.forName(" in it }
        assertTrue("runCatching" in lookup) { "the Class.forName of the service must sit inside runCatching" }
        val invokes = code.withIndex().filter { (_, line) -> ".invoke(" in line && "runCatching" !in line }
        assertTrue(invokes.isNotEmpty()) { "$GATEWAY no longer invokes by reflection; the seam has moved" }
        assertTrue(code.any { "catch (e: ReflectiveOperationException)" in it }) {
            "a reflective call that throws must surface as a ToolException naming the cause"
        }
    }

    private companion object {

        const val GATEWAY = "PluginDetailsGateway.kt"
        const val SERVICE_CLASS = "PluginDetailsService"
        const val SERVICE = "com.intellij.ide.plugins.PluginDetailsService"

        val LISTING = listOf("PluginManagerCore.plugins", "PluginManagerCore.getPlugins", "PluginManagerCore.loadedPlugins", "PluginManager.getPlugins")
    }
}
