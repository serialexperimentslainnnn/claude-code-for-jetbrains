package dev.lain.claudejb

import java.io.File

internal object MainSources {

    fun files(): List<File> =
        root("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .sortedBy { it.path }

    fun codeOf(file: File): List<String> {
        var inBlockComment = false
        return file.readLines().map { raw ->
            val trimmed = raw.trimStart()
            when {
                inBlockComment -> "".also { if (trimmed.contains("*/")) inBlockComment = false }
                trimmed.startsWith("/*") -> "".also { if (!trimmed.contains("*/")) inBlockComment = true }
                trimmed.startsWith("*") || trimmed.startsWith("//") -> ""
                else -> withoutLineComment(withoutStringLiterals(raw))
            }
        }
    }

    fun withoutStringLiterals(line: String): String =
        STRING_LITERAL.replace(line) { match ->
            TEMPLATE.findAll(match.value).joinToString(" ", prefix = " ", postfix = " ") {
                it.groupValues[1] + it.groupValues[2]
            }
        }

    fun root(path: String): File =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isDirectory }
            ?: error("could not locate $path from ${File("").absolutePath}")

    private fun withoutLineComment(line: String): String = line.substringBefore("//")

    private val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    private val TEMPLATE = Regex("""\${'$'}\{([^}]*)}|\${'$'}(\w+)""")
}
