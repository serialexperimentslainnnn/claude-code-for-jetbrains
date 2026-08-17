package dev.lain.claudejb

import java.io.File

/**
 * `src/main/kotlin`, reduced to the text a reachability question may be asked of — **one reducer, shared by
 * every gate that asks one**.
 *
 * It exists because there is more than one such gate now ([ReachabilityContractTest] for what the whole tree
 * names, [PrivateReachabilityContractTest] for what a single file names), and they must agree about what
 * counts as a mention. Two reducers is how one gate starts reporting as dead what the other reads as live —
 * the same reason this repository keeps exactly one JSONL parser.
 *
 * THE THREE REDUCTIONS, each of which is a way a dead declaration reports itself as live:
 *  1. **A comment is not a reference.** This codebase's KDoc is dense with `[Symbol]` links, and dead code
 *     here is the best documented of all, because it was written in good faith.
 *  2. **A string literal is not a reference** — but the template expressions inside it are, because an
 *     interpolated call really is a call. The literal's own text goes; `$ident` and `${'$'}{expr}` stay.
 *  3. **Line numbers survive**, so a finding can name the line it is on: the reduction blanks lines, never
 *     removes them.
 *
 * The body of a MULTI-LINE raw string is left as it stands, and that is the one place this is deliberately
 * generous: a symbol named inside one still counts as a reference. Tracking the fences would make it stricter
 * in a corner it has never been fooled by, at the price of a second parser state that can mis-close and start
 * reporting live declarations as dead — and a gate that cries wolf gets deleted.
 */
internal object MainSources {

    /** Every Kotlin file under `src/main/kotlin`, in a stable order. */
    fun files(): List<File> =
        root("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .sortedBy { it.path }

    /** [file]'s code, one entry per original line — see the class doc for what is taken out. */
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

    /** Reduction 2: the literal's own text goes, the template expressions inside it stay. */
    fun withoutStringLiterals(line: String): String =
        STRING_LITERAL.replace(line) { match ->
            TEMPLATE.findAll(match.value).joinToString(" ", prefix = " ", postfix = " ") {
                it.groupValues[1] + it.groupValues[2]
            }
        }

    /** Resolves a directory whether the test runs from the module dir or from the repo root. */
    fun root(path: String): File =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isDirectory }
            ?: error("could not locate $path from ${File("").absolutePath}")

    private fun withoutLineComment(line: String): String = line.substringBefore("//")

    private val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    /**
     * The two spellings of an interpolated reference, `${'$'}{expr}` and `${'$'}ident`. The dollar is escaped for
     * the REGEX as well as produced for Kotlin: unescaped it is the end-of-input anchor, so the pattern compiles
     * and matches nothing — which costs no error anywhere and turns every symbol referenced only by
     * interpolation into a reported orphan.
     */
    private val TEMPLATE = Regex("""\${'$'}\{([^}]*)}|\${'$'}(\w+)""")
}
