package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PackageDependencyContractTest {

    private val imports: List<Import> = MainSources.files().flatMap { file ->
        val from = layerOf(packageOf(file))
        MainSources.codeOf(file)
            .map { it.trim() }
            .filter { it.startsWith(IMPORT_PREFIX) }
            .map { line -> Import(file, from, layerOf(packageOfFqn(line.removePrefix(IMPORT_PREFIX).substringBefore(" as ").trim()))) }
    }

    @Test
    fun `the scan sees the whole tree`() {
        assertTrue(MainSources.files().size >= MIN_SOURCES) { "only ${MainSources.files().size} sources found" }
        assertTrue(imports.size >= MIN_IMPORTS) { "only ${imports.size} in-repo imports found" }
    }

    @Test
    fun `every package sits in a declared layer`() {
        val orphans = MainSources.files().map { packageOf(it) }.distinct().filter { layerOf(it) == null }
        assertEquals(emptyList<String>(), orphans) {
            "A package exists that no layer claims; add it to LAYERS with the layers it may import."
        }
    }

    @Test
    fun `every layer imports only the layers below it`() {
        val offenders = imports
            .filter { it.from != null && it.to != null && it.to != it.from && it.to !in ALLOWED.getValue(it.from) }
            .map { "${it.file.relativeTo(MainSources.root(SOURCE_ROOT))}: ${it.from} -> ${it.to}" }
        assertEquals(emptyList<String>(), offenders) {
            "A layer reaches above itself. model/ never imports controller/ or view/, controller/session and " +
                "controller/mcp open diff editors and nothing else of view/, and the wire and the guard stay below everything: the " +
                "direction is the architecture."
        }
    }

    @Test
    fun `the wire and the guard know nothing of the platform`() {
        val offenders = MainSources.files()
            .filter { file -> PLATFORM_FREE.any { packageOf(file).startsWith(it) } }
            .flatMap { file ->
                MainSources.codeOf(file).map { it.trim() }
                    .filter { line -> PLATFORM_IMPORTS.any { line.startsWith("import $it") } }
                    .map { "${file.name}: $it" }
            }
        assertEquals(emptyList<String>(), offenders) {
            "model/protocol, model/permission, model/bridge and model/mcp unit-test on a plain JVM; a platform import there " +
                "drags the IDE into every test that touches them."
        }
    }

    private fun packageOf(file: File): String {
        val rel = file.relativeTo(MainSources.root(SOURCE_ROOT)).invariantSeparatorsPath
        return rel.removePrefix("$PACKAGE_ROOT/").substringBeforeLast('/', "")
    }

    private data class Import(val file: File, val from: String?, val to: String?)

    private companion object {

        const val SOURCE_ROOT = "src/main/kotlin"
        const val PACKAGE_ROOT = "dev/lain/claudejb"
        const val IMPORT_PREFIX = "import dev.lain.claudejb."
        const val MIN_SOURCES = 100
        const val MIN_IMPORTS = 300

        fun packageOfFqn(fqn: String): String {
            val segments = fqn.split('.')
            val packageSegments = segments.dropLast(1).takeWhile { it.first().isLowerCase() }
            return packageSegments.joinToString("/")
        }

        fun layerOf(pkg: String): String? =
            ALLOWED.keys.filter { pkg == it || pkg.startsWith("$it/") }.maxByOrNull { it.length }

        const val UTIL = "util"
        const val PROTOCOL = "model/protocol"
        const val PERMISSION = "model/permission"
        const val BRIDGE = "model/bridge"
        const val SETTINGS = "model/settings"
        const val DIFF = "model/diff"
        const val CONTEXT = "model/context"
        const val GIT = "model/git"
        const val VULN = "model/vuln"
        const val SESSION = "model/session"
        const val MCP = "model/mcp"
        const val C_MCP = "controller/mcp"
        const val C_PROCESS = "controller/process"
        const val C_VULN = "controller/vuln"
        const val C_CONTEXT = "controller/context"
        const val C_GIT = "controller/git"
        const val C_GITHUB = "controller/github"
        const val C_DB = "controller/db"
        const val C_SESSION = "controller/session"
        const val VIEW = "view"
        const val V_DIFF = "view/diff"
        const val C_BRIDGE = "controller/bridge"
        const val C_COMMANDS = "controller/commands"
        const val C_ACTIONS = "controller/actions"

        val MODEL = setOf(PROTOCOL, PERMISSION, BRIDGE, SETTINGS, DIFF, CONTEXT, GIT, VULN, SESSION, MCP, UTIL)

        val ALLOWED: Map<String, Set<String>> = mapOf(
            UTIL to setOf(),
            PROTOCOL to setOf(UTIL),
            MCP to setOf(UTIL),
            C_MCP to MODEL + setOf(C_GIT, C_GITHUB, C_DB, V_DIFF),
            C_GITHUB to MODEL,
            C_DB to MODEL,
            DIFF to setOf(PROTOCOL, UTIL),
            CONTEXT to setOf(DIFF, PROTOCOL, UTIL),
            GIT to setOf(DIFF, UTIL),
            PERMISSION to setOf(PROTOCOL, DIFF, UTIL),
            BRIDGE to setOf(PROTOCOL, UTIL),
            SETTINGS to setOf(PERMISSION, PROTOCOL, DIFF, UTIL),
            VULN to setOf(SETTINGS, PROTOCOL, UTIL),
            SESSION to MODEL,
            C_PROCESS to MODEL,
            C_VULN to MODEL,
            C_CONTEXT to MODEL + setOf(C_MCP),
            C_GIT to MODEL,
            C_SESSION to MODEL + setOf(C_PROCESS, C_VULN, C_GIT, C_CONTEXT, C_MCP, V_DIFF),
            V_DIFF to MODEL,
            VIEW to MODEL + setOf(C_SESSION, C_GIT, C_VULN, C_PROCESS, C_CONTEXT, V_DIFF, C_COMMANDS, C_BRIDGE),
            C_BRIDGE to MODEL + setOf(C_SESSION, C_GIT, C_VULN, C_PROCESS, C_CONTEXT, V_DIFF, VIEW, C_COMMANDS),
            C_COMMANDS to MODEL + setOf(C_SESSION, C_GIT, C_VULN, C_PROCESS, C_CONTEXT, V_DIFF, VIEW, C_BRIDGE),
            C_ACTIONS to MODEL + setOf(C_SESSION, C_GIT, C_VULN, C_PROCESS, C_CONTEXT, V_DIFF, VIEW, C_BRIDGE, C_COMMANDS),
        )

        val PLATFORM_FREE = setOf(PROTOCOL, PERMISSION, BRIDGE, MCP)

        val PLATFORM_IMPORTS = listOf("com.intellij", "org.cef", "java.awt", "javax.swing")
    }
}
