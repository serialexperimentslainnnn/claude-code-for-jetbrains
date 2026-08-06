package dev.lain.claudejb.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A class-body property must be declared BEFORE the `init` block that can reach it.
 *
 * REGRESSION THIS PINS (5.0.0): `JcefChatPanel.pendingUntilReady` was declared ~90 lines below the `init` block
 * that calls [dev.lain.claudejb.ui.JcefChatPanel] `whenReady` three times. Kotlin runs property initializers and
 * `init` blocks in declaration order, so the list was still null while `init` ran:
 *
 * ```
 * java.lang.NullPointerException: Cannot invoke "java.util.Collection.add(Object)"
 *   because "this.pendingUntilReady" is null
 *     at JcefChatPanel.whenReady(JcefChatPanel.kt:173)
 *     at JcefChatPanel.<init>(JcefChatPanel.kt:91)
 * ```
 *
 * It threw inside the constructor, so it took the whole tab with it: no chat could be opened or restored — the
 * plugin was unusable, not degraded. The compiler does NOT catch this: it reports a direct reference in an
 * initializer, but the read here happens inside a *function* called from `init`, which it cannot see through.
 *
 * The nullable and primitive fields nearby (`lastUsage`, `lastUsageAt`) had the same defect and stayed silent —
 * they read as null/0 instead of throwing — which is why this is a source contract rather than a note in a
 * review: the loud version of the bug is the lucky one.
 *
 * Deliberately a source scan and not a runtime test. Constructing a [dev.lain.claudejb.ui.JcefChatPanel] needs a
 * live IDE and a JCEF browser, which is exactly why `ui/` is excluded from coverage and why nothing caught this.
 * Reading the file costs nothing and covers the whole class of defect.
 *
 * The 4-space indent is what scopes this to top-level class bodies: a nested `init` (an anonymous
 * `object : JComponent()`, as in `ChatTheme.avatarLabel()`) sits deeper and is correctly ignored — its
 * enclosing properties are not initialised by it.
 */
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

    /** Resolves `src/main/kotlin` whether the test runs from the module dir or the repo root. */
    private fun sourceRoot(): File =
        sequenceOf(File("src/main/kotlin"), File("../src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File("").absolutePath}")
}
