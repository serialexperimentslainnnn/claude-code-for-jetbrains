package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoCommentsContractTest {

    @Test
    fun `the scan sees the whole tree`() {
        assertTrue(MainSources.files().size >= MIN_SOURCES) { "only ${MainSources.files().size} sources found" }
    }

    @Test
    fun `no main source carries a comment`() {
        val root = MainSources.root(SOURCE_ROOT)
        val offenders = MainSources.files().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, raw) -> isComment(raw) }
                .map { (index, raw) -> "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${raw.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders) {
            "The reasoning goes into a name, a test or the commit message, never into a comment. " +
                "The only text allowed is a machine-read pragma."
        }
    }

    private fun isComment(raw: String): Boolean {
        val trimmed = raw.trimStart()
        if (PRAGMAS.any { trimmed.contains(it) }) return false
        if (trimmed == "*" || COMMENT_OPENERS.any { trimmed.startsWith(it) }) return true
        val code = MainSources.withoutStringLiterals(raw)
        return code.contains("//") || code.contains("/*")
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/kotlin"
        const val MIN_SOURCES = 100
        val PRAGMAS = listOf("noinspection", "MAP:GENERATED")
        val COMMENT_OPENERS = listOf("//", "/*", "* ")
    }
}
