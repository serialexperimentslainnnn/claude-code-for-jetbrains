package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every `private` declaration must be used by its own file.** The neighbouring [ReachabilityContractTest]
 * asks the same question of the whole tree and declares two shapes out of scope: top-level `private`
 * declarations (it skips any line starting with `private `) and anything NESTED (it skips indented lines).
 * This is that gap, and it is a gap with a body count.
 *
 * **What fell through it.** Six `AnAction` subclasses — New Chat, Log out, Interrupt, Commands, Git, Close All
 * Diffs — lived as `private class`es inside `ClaudeToolWindowFactory`. When their buttons moved into the
 * composer and `setTitleActions` stopped being called, all six became unreachable: 90 lines of live-looking
 * UI code, with icons, enablement rules and threading arguments, registered by nothing. The Kotlin compiler
 * did not warn, detekt did not warn, `koverVerify` was happy and `ReachabilityContractTest` passed. Nothing in
 * this build could see them.
 *
 * **Why this question is exact where the other one is a judgement call.** `private` means *this file and
 * nowhere else* — so unlike the tree-wide scan, there is no cross-file guessing, no import to resolve and no
 * ambiguity about which `Owner.member` a bare name belongs to. If the name is not written again in the file
 * that declares it, nothing can be calling it. That is a fact about Kotlin's visibility rules rather than a
 * heuristic about this codebase's style, which is what makes the gate safe to fail the build on.
 *
 * The reduction — comments out, string literals out, template expressions kept — is [MainSources], shared with
 * the tree-wide scan so the two cannot disagree about what counts as a mention.
 *
 * WHAT IS SKIPPED, and why each one would otherwise be a false alarm:
 *  - **`override`s**: called through the supertype, so their own name proves nothing.
 *  - **`private constructor`**: invoked by writing the CLASS's name, never its own.
 *  - **`private companion object`** and other anonymous forms: there is no name to look for.
 *  - **The declaration's own body**: a class that names itself in its own `toString`, a recursive function, a
 *    factory returning its own type — all natural, and none of them evidence that anything outside asks.
 *
 * THE BLIND SPOT, named rather than left to be discovered: **two dead private declarations that name each
 * other both read as live.** The question is "is this name written elsewhere in the file", not "is it reachable
 * from something that runs". Closing that needs a call graph, which needs a compiler — the same boundary
 * [ReachabilityContractTest] draws for the same reason.
 */
class PrivateReachabilityContractTest {

    private val files: List<File> = MainSources.files()

    @Test
    fun `the scan reaches the sources and finds private declarations to judge`() {
        // Without this the assertion below is `[] is empty`, which is green forever and proves nothing. Both
        // halves fail loudly: the wrong tree, and a declaration pattern that has stopped matching the style.
        assertTrue(files.size > MIN_SOURCES) {
            "Only ${files.size} Kotlin sources found from ${File("").absolutePath} — this gate is looking at " +
                "the wrong tree and would pass whatever the code did."
        }
        val found = files.sumOf { declarationsIn(MainSources.codeOf(it)).size }
        assertTrue(found > MIN_DECLARATIONS) {
            "Parsed $found private declarations across ${files.size} files. The pattern has stopped matching."
        }
    }

    @Test
    fun `every private declaration is used by the file that declares it`() {
        val orphans = files.flatMap { file ->
            val code = MainSources.codeOf(file)
            declarationsIn(code)
                .filterNot { usedElsewhereIn(code, it) }
                .map { "${it.kind} ${it.name} — ${file.path}:${it.line + 1}" }
        }
        assertTrue(orphans.isEmpty()) {
            "These are `private`, so only their own file could ever call them — and it does not. Nothing else " +
                "in this build can see that: the compiler stayed quiet, detekt stayed quiet, and their tests " +
                "(if any) kept coverage up. Wire each one to its caller, or delete it.\n" +
                orphans.joinToString("\n")
        }
    }

    /**
     * The detection, driven over a synthetic file whose answer is known.
     *
     * A verdict only ever observed green cannot be told apart from one that reports nothing — and this gate
     * spends most of its life green, which is exactly when a silently broken pattern would go unnoticed.
     */
    @Test
    fun `reports a private declaration nothing in its file names`() {
        val code = MainSources.codeOf(syntheticFile())

        val found = declarationsIn(code).filterNot { usedElsewhereIn(code, it) }.map { it.name }

        assertEquals(listOf("Unreached"), found) {
            "Expected only the unreferenced class. Found: $found — the scan is judging something else."
        }
    }

    /**
     * A file with one of each case the scan must get right: a private class that IS constructed, one that is
     * not, a private function called from the body, a private one named only in a comment and in a string,
     * and an `override` that must not be judged at all.
     */
    private fun syntheticFile(): File {
        val file = File.createTempFile("private-reachability", ".kt")
        file.deleteOnExit()
        file.writeText(
            """
            package example

            internal class Host(private val given: String) {
                private val held = Reached()
                override fun toString(): String = describe() + held + given
                private fun describe(): String = "Unreached is not a reference, and neither is [Unreached]"
                private class Reached
                private class Unreached
            }
            """.trimIndent(),
        )
        return file
    }

    /** True when [declaration]'s name is written anywhere in [code] outside its own declaration and body. */
    private fun usedElsewhereIn(code: List<String>, declaration: Declaration): Boolean {
        val name = Regex("""\b${Regex.escape(declaration.name)}\b""")
        val body = bodyOf(code, declaration.line)
        return code.indices.any { index -> index !in body && name.containsMatchIn(code[index]) }
    }

    /**
     * The line range a declaration owns, as an inclusive interval — its own line plus the braces it opens.
     *
     * Counted on the REDUCED code, where a brace inside a comment or a string literal is already gone. A
     * declaration that opens no brace on its own line (`private val x = y`) owns just that line.
     *
     * **A CONSTRUCTOR PROPERTY owns nothing but its line**, and getting that wrong is what this rule is for:
     * `class Foo(private val bar: Bar) {` opens the class's braces on the same line, so brace-counting from
     * there swallowed the whole class — which is precisely where `bar` is used. Ten live declarations were
     * reported dead by that alone, every one of them a constructor property of a collaborator.
     */
    private fun bodyOf(code: List<String>, from: Int): IntRange {
        if (TYPE_ON_LINE.containsMatchIn(code[from])) return from..from
        var depth = 0
        var line = from
        while (line < code.size) {
            depth += code[line].count { it == '{' } - code[line].count { it == '}' }
            if (line > from && depth <= 0) break
            if (line == from && depth == 0) break
            line++
        }
        return from..minOf(line, code.size - 1)
    }

    private fun declarationsIn(code: List<String>): List<Declaration> =
        code.indices.mapNotNull { index ->
            val line = code[index]
            if (SKIPPED.containsMatchIn(line)) return@mapNotNull null
            DECLARATION.find(line)?.let { Declaration(it.groupValues[2], it.groupValues[1], index) }
        }

    private data class Declaration(val name: String, val kind: String, val line: Int)

    private companion object {

        const val MIN_SOURCES = 100
        const val MIN_DECLARATIONS = 100

        /**
         * `private class Foo` / `private fun bar(` / `private val baz` — with any modifiers in between, since
         * `private inner class`, `private suspend fun` and `private const val` are all this shape.
         */
        val DECLARATION = Regex("""\bprivate\b[\w\s]*?\b(class|object|interface|fun|val|var)\s+(\w+)""")

        /**
         * Shapes this scan must not judge. `override` is called through its supertype; a constructor is
         * invoked by the class's name; a `private set` is part of the property above it, not a declaration of
         * its own; a type parameter list before the name (`private fun <T> of`) breaks the capture; and an
         * EXTENSION (`private fun List<String>.lastMatching`) is called on its receiver, so the name this
         * pattern would capture is the receiver's type rather than the function's.
         */
        val SKIPPED = Regex(
            """\boverride\b|\bprivate\s+constructor\b|\bprivate\s+set\b|""" +
                """\bprivate\s+fun\s*<|\bprivate\s+fun\s+\w+\s*[<.]""",
        )

        /**
         * A type declaration on the same line — which makes any `private val` on it a CONSTRUCTOR PROPERTY,
         * whose scope is the body those braces open rather than something to exclude. `value class` and
         * `enum class` are the same shape and were among the false alarms.
         */
        val TYPE_ON_LINE = Regex("""\b(class|object|interface)\s+\w""")
    }
}
