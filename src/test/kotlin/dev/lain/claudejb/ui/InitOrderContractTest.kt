package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class InitOrderContractTest {

    private val classBodyInit = Regex("""^ {4}init \{""")
    private val classBodyProperty = Regex("""^ {4}(?:private |internal |protected )?(?:val|var) """)

    @Test
    fun `no class-body property is declared after the init block that could use it`() {
        val offenders = mutableListOf<String>()

        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            val initAt = lines.indexOfFirst { classBodyInit.containsMatchIn(it) }
            if (initAt < 0) return@forEach
            lines.drop(initAt + 1).forEachIndexed { offset, line ->
                if (classBodyProperty.containsMatchIn(line)) {
                    offenders += "${file.name}:${initAt + offset + 2}: ${line.trim()}"
                }
            }
        }

        assertTrue(offenders.isEmpty()) {
            "These properties are declared AFTER their class's init block, so they are still null/0 while it " +
                "runs. Move them above `init`.\n" + offenders.joinToString("\n")
        }
    }

    private fun sourceRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
