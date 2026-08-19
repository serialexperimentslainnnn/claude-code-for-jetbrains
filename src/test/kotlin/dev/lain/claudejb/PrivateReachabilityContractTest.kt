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

    @Test
    fun `reports a private declaration nothing in its file names`() {
        val code = MainSources.codeOf(syntheticFile())

        val found = declarationsIn(code).filterNot { usedElsewhereIn(code, it) }.map { it.name }

        assertEquals(listOf("Unreached"), found) {
            "Expected only the unreferenced class. Found: $found — the scan is judging something else."
        }
    }

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

    private fun usedElsewhereIn(code: List<String>, declaration: Declaration): Boolean {
        val name = Regex("""\b${Regex.escape(declaration.name)}\b""")
        val body = bodyOf(code, declaration.line)
        return code.indices.any { index -> index !in body && name.containsMatchIn(code[index]) }
    }

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

        val DECLARATION = Regex("""\bprivate\b[\w\s]*?\b(class|object|interface|fun|val|var)\s+(\w+)""")

        val SKIPPED = Regex(
            """\boverride\b|\bprivate\s+constructor\b|\bprivate\s+set\b|""" +
                """\bprivate\s+fun\s*<|\bprivate\s+fun\s+\w+\s*[<.]""",
        )

        val TYPE_ON_LINE = Regex("""\b(class|object|interface)\s+\w""")
    }
}
