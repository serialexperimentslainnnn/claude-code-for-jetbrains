package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class InternalPlatformApiContractTest {

    private val sources: List<File> = listOf("src/main/kotlin", "src/main/java")
        .map(::File)
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") } }
        .sortedBy { it.path }

    @Test
    fun `the sources exist and this test is actually looking at them`() {
        assertTrue(sources.isNotEmpty(), "No production sources found; this contract would pass vacuously")
    }

    @Test
    fun `no production source reaches an internal platform API`() {
        val hits = sources.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                INTERNAL_SYMBOLS.filter { it in line }.map { "${file.path}:${index + 1} -> $it" }
            }
        }
        assertEquals(
            emptyList<String>(),
            hits,
            "Internal platform API in production code: $hits. These carry @ApiStatus.Internal, which the " +
                "compiler does not warn about and which promises nothing across builds. Every one of them has a " +
                "public replacement; if a new one is genuinely unavoidable, it is a decision to take out loud, " +
                "not a line to slip in.",
        )
    }

    private companion object {

        val INTERNAL_SYMBOLS = listOf(
            "DaemonCodeAnalyzerImpl",
            "PlatformUtils",
            "getModuleRootManager",
            "runReadActionInSmartMode",
            "repeatUntilPassesInSmartMode",
            "updateProjectModel",
            "projectIndexableFiles",
            "ensureUpToDate",
            "putUserLabel",
            "navigateToTextEditor",
            "createLocalShellWidget",
            "runWriteCommandAction",
        )
    }
}
