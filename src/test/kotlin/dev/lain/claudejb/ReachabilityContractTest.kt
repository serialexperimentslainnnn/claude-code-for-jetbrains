package dev.lain.claudejb

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every declaration in `src/main` must be named by something else in `src/main`.** A declaration whose only
 * mentions are its own file, its own KDoc and its own tests is not code the plugin runs — it is code the plugin
 * carries, and it is carried at full price: it is read in reviews, it is migrated, it is kept compiling.
 *
 * WHY THIS HAS TO BE A GATE. detekt analyses `src/main` and `src/test` in ONE invocation (`build.gradle.kts`,
 * `source.setFrom(files("src/main/kotlin", "src/test/kotlin"))`). A declaration that nothing calls but its own
 * test therefore satisfies every unused-code rule, and that same test then raises its line coverage for
 * `koverVerify`. **A dead file with a good test suite scores better than a live one**, and no other gate in this
 * build can tell the two apart. What it looks like in practice is a declaration that is implemented,
 * documented, tested — and reachable from nothing.
 *
 * WHAT COUNTS AS A REFERENCE — the three exclusions are the whole test, because each one is a way a dead
 * declaration reports itself as live:
 *  1. **A comment is not a reference.** This codebase's KDoc is dense with `[Symbol]` links, and dead code here
 *     is the BEST documented, because it was written in good faith. `GitReadOnlyContractTest` already treats a
 *     symbol named in prose as not a usage; this is the same rule.
 *  2. **A string literal is not a reference.** A class that names itself in its own `toString` would otherwise
 *     certify itself. Template expressions inside a string are kept: an interpolated call really is a call.
 *  3. **The declaration's own body is not a reference.** A companion factory returning its own type is the most
 *     natural shape in the world, and it says nothing about whether anything outside ever asks for one.
 *
 * WHAT IS EXEMPT, and nothing else is: **platform entry points**, which are reached from the plugin
 * descriptors rather than from Kotlin. They are resolved by READING those descriptors, never from a list kept
 * here: a hardcoded list goes stale silently, and it would then exempt a class the descriptor had stopped
 * naming. There is no per-declaration exemption and there is deliberately no way to grant one: a finding here
 * is answered by wiring the declaration or deleting it, and an exemption is how it gets answered by neither.
 *
 * SCOPE, and its two declared limits. The scan covers top-level declarations and the members of top-level
 * `object`s — the shapes whose call sites are spelled unambiguously in the source (`Owner.member`,
 * `Owner::member`, or an import of it). Members of CLASSES are reached through an instance whose type only a
 * compiler can resolve, and EXTENSIONS are called on their receiver rather than on their owner; both are
 * skipped, because a guess in either direction is worse than a declared gap. `override`s are skipped too: they
 * are called through their supertype, so a scan for their own name proves nothing.
 *
 * TWO BLIND SPOTS THIS SCAN HAS, named because a gate that lists three of its five limits reads as exhaustive
 * and gets trusted for the two it left out:
 *  - **Mutual references between dead declarations read as live.** The question asked is "is this name written
 *    anywhere else", not "is it reachable from anything that runs". Two dead declarations that name each other
 *    each certify the other and neither is reported. One level down, the same applies inside an object: a dead
 *    member whose only caller is another dead member of the same object is invisible, because a member used by
 *    its own object counts as referenced — and that clause is right, since an object legitimately assembles
 *    what it exports out of its own parts. Closing this needs transitive reachability, which needs a call
 *    graph, which needs a compiler; that is the same boundary the scope above draws.
 *  - **The entry-point exemption matches a SIMPLE name, and it is global.** A class the descriptors name is
 *    exempt everywhere, so an unrelated top-level declaration that happens to share that simple name inherits
 *    the exemption. Reading the descriptors is what stops the exemption going stale; this is the one way it
 *    still can. The fix, if a name is ever reused: match the fully-qualified name, which means tracking each
 *    declaration's package as well as its name.
 */
class ReachabilityContractTest {

    private val sources: List<Source> = mainSources()

    @Test
    fun `the scan reaches the sources and the descriptors it depends on`() {
        assertTrue(sources.size > MIN_SOURCES) {
            "Only ${sources.size} Kotlin sources found from ${File("").absolutePath} — this gate is looking at " +
                "the wrong tree and would pass whatever the code did."
        }
        assertTrue(sources.sumOf { it.blocks.size } > MIN_DECLARATIONS) {
            "Parsed ${sources.sumOf { it.blocks.size }} top-level declarations out of ${sources.size} files. " +
                "The declaration pattern has stopped matching this codebase's style."
        }
        assertTrue(entryPoints().isNotEmpty()) {
            "No dev.lain.claudejb class is named in ${DESCRIPTORS.joinToString()}. Either the descriptors moved " +
                "or they stopped declaring the plugin's entry points — both make every finding below suspect."
        }
    }

    @Test
    fun `every top-level declaration is named elsewhere in the main sources`() {
        val entryPoints = entryPoints()
        val orphans = sources.flatMap { source ->
            source.blocks
                .filterNot { it.name in entryPoints }
                .filterNot { referencedOutsideItself(source, it) }
                .map { "${it.kind} ${it.name} — ${source.file.path}:${it.from + 1}" }
        }
        assertTrue(orphans.isEmpty()) {
            "Nothing in src/main names these declarations. Their tests keep detekt quiet and kover happy, which " +
                "is exactly why nobody notices. Each one is either WIRED to the code that should be calling it, " +
                "or DELETED — a third option would only mean the gate stops being able to tell.\n" +
                orphans.joinToString("\n")
        }
    }

    @Test
    fun `every member of a top-level object is named elsewhere in the main sources`() {
        val orphans = sources.flatMap { source ->
            source.blocks.filter { it.kind == "object" }.flatMap { orphanMembers(source, it) }
        }
        assertTrue(orphans.isEmpty()) {
            "Nothing in src/main calls these members. The object around them is alive, which is what makes this " +
                "invisible: the class-level question answers yes and the member is never asked about. Wire each " +
                "one to its caller, or delete it.\n" + orphans.joinToString("\n")
        }
    }

    /**
     * Exclusion 2 has two halves and this pins both: the literal's own text goes, the template expressions inside
     * it stay. The second half is what makes an interpolated call count as the call it is, and it is invisible
     * when it breaks — [TEMPLATE] matching nothing costs no error and no failure here, it silently reports every
     * symbol referenced only by interpolation as an orphan, i.e. live code as dead. Asserted against the
     * reduction itself, never against the pattern's source text: the way this breaks is a pattern that reads
     * correctly and matches nothing.
     */
    @Test
    fun `a symbol named only inside a string template survives the reduction`() {
        val bare = withoutStringLiterals("""    val key = "${'$'}BARE_PREFIX${'$'}suffix" to window""")
        val braced = withoutStringLiterals("""    val css = "width:${'$'}{BRACED_WIDTH}px" + tail""")

        assertTrue(Regex("""\bBARE_PREFIX\b""").containsMatchIn(bare)) {
            "The `${'$'}IDENT` form did not survive the reduction: <$bare>"
        }
        assertTrue(Regex("""\bsuffix\b""").containsMatchIn(bare)) {
            "A second `${'$'}IDENT` in the same literal did not survive the reduction: <$bare>"
        }
        assertTrue(Regex("""\bBRACED_WIDTH\b""").containsMatchIn(braced)) {
            "The `${'$'}{IDENT}` form did not survive the reduction: <$braced>"
        }
        assertTrue("width" !in braced) {
            "The literal's own text survived: <$braced>. A string that merely names a symbol is not a reference " +
                "to it — only the template expressions inside it are."
        }
    }

    /** True when [block]'s name appears in any main source, anywhere except inside the block itself. */
    private fun referencedOutsideItself(owner: Source, block: Block): Boolean {
        val name = Regex("""\b${block.name}\b""")
        return sources.any { source ->
            val text =
                if (source === owner) owner.textWhere { it < block.from || it >= block.to } else source.text
            name.containsMatchIn(text)
        }
    }

    private fun orphanMembers(owner: Source, obj: Block): List<String> =
        (obj.from + 1 until obj.to).mapNotNull { index ->
            val member = memberName(owner.code[index]) ?: return@mapNotNull null
            val id = "${obj.name}.$member"
            if (memberReferenced(owner, obj, member, index)) null else "$id — ${owner.file.path}:${index + 1}"
        }

    /**
     * A member is referenced when another file spells it out (`Owner.member`, `Owner::member`, or an import of
     * it followed by a bare use), or when the object's own body uses it to build something else it exposes.
     */
    private fun memberReferenced(owner: Source, obj: Block, member: String, line: Int): Boolean {
        val qualified = Regex("""\b${obj.name}\s*(?:\.|::)\s*$member\b""")
        val imported = Regex("""import\s+[\w.]*\.${obj.name}\.(?:$member\b|\*)""")
        val bare = Regex("""\b$member\b""")
        val fromOutside = sources.any { source ->
            val text = if (source === owner) owner.textWhere { it != line } else source.text
            qualified.containsMatchIn(text) || (imported.containsMatchIn(text) && bare.containsMatchIn(text))
        }
        return fromOutside ||
            bare.containsMatchIn(owner.textWhere { it > obj.from && it < obj.to && it != line })
    }

    /** The simple names the plugin descriptors declare: these are entered from the platform, not from Kotlin. */
    private fun entryPoints(): Set<String> =
        DESCRIPTORS
            .map { resourceRoot().resolve(it) }
            .filter { it.isFile }
            .flatMap { file -> PLUGIN_CLASS.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()

    private fun mainSources(): List<Source> =
        sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { Source(it, codeOf(it)) }
            .toList()
            .sortedBy { it.file.path }

    /**
     * The file's CODE, one entry per original line so that line numbers survive: comment lines are blanked, and
     * inside the rest every string literal is replaced by the template expressions it contained, if any.
     *
     * The body of a MULTI-LINE raw string is left as it stands, and that is the one place this scan is
     * deliberately generous: a symbol named inside one still counts as a reference. Tracking the fences would
     * make the gate stricter in a corner it has never been fooled by, at the price of a second parser state that
     * can mis-close and start reporting live declarations as dead — and a gate that cries wolf gets deleted.
     */
    private fun codeOf(file: File): List<String> {
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

    private fun withoutStringLiterals(line: String): String =
        STRING_LITERAL.replace(line) { match ->
            TEMPLATE.findAll(match.value).joinToString(" ", prefix = " ", postfix = " ") {
                it.groupValues[1] + it.groupValues[2]
            }
        }

    private fun withoutLineComment(line: String): String = line.substringBefore("//")

    /** Resolves a main-source directory whether the test runs from the module dir or the repo root. */
    private fun sourceRoot(): File = resolveFromEitherRoot("src/main/kotlin")

    private fun resourceRoot(): File = resolveFromEitherRoot("src/main/resources/META-INF")

    private fun resolveFromEitherRoot(path: String): File =
        sequenceOf(File(path), File("../$path")).firstOrNull { it.isDirectory }
            ?: error("could not locate $path from ${File("").absolutePath}")

    /** The name a top-level `object` member declares, or null when the line is not one this scan can judge. */
    private fun memberName(line: String): String? {
        if (SKIPPED_MODIFIER.containsMatchIn(line)) return null
        val match = MEMBER_DECLARATION.find(line) ?: return null
        return match.groupValues[1].takeIf { match.groupValues[2].isEmpty() }
    }

    /** One Kotlin file, reduced to code, with its top-level declarations located. */
    private class Source(val file: File, val code: List<String>) {

        val blocks: List<Block> = parse(code)

        val text: String = code.joinToString("\n")

        fun textWhere(keep: (Int) -> Boolean): String =
            code.indices.filter(keep).joinToString("\n") { code[it] }

        private companion object {

            /** Each top-level declaration owns the lines from its own down to the next one's. */
            fun parse(code: List<String>): List<Block> {
                val found = code.indices.mapNotNull { index -> blockAt(code, index) }
                return found.mapIndexed { position, block ->
                    if (position + 1 < found.size) block.copy(to = found[position + 1].from) else block
                }
            }

            fun blockAt(code: List<String>, index: Int): Block? {
                val line = code[index]
                if (line.isEmpty() || line.first().isWhitespace() || line.startsWith("private ")) return null
                val match = TOP_LEVEL_DECLARATION.find(line) ?: return null
                if (match.groupValues[3].isNotEmpty()) return null
                return Block(match.groupValues[2], match.groupValues[1], index, code.size)
            }
        }
    }

    /** A top-level declaration and the half-open line range `[from, to)` that belongs to it. */
    private data class Block(val name: String, val kind: String, val from: Int, val to: Int)

    private companion object {

        /** The descriptors the platform reads. Whatever they name is entered from outside Kotlin. */
        val DESCRIPTORS = listOf("plugin.xml", "claude-git.xml", "claude-terminal.xml")

        const val MIN_SOURCES = 100
        const val MIN_DECLARATIONS = 100

        val PLUGIN_CLASS = Regex("""dev\.lain\.claudejb\.[\w.]*?([A-Z]\w*)\b""")

        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

        /**
         * The two spellings of an interpolated reference, `${expr}` and `$ident`. The dollar is escaped for the
         * REGEX as well as produced for Kotlin: unescaped it is the end-of-input anchor, so the pattern compiles
         * and matches nothing — which costs no error anywhere and turns every symbol referenced only by
         * interpolation into a reported orphan.
         */
        val TEMPLATE = Regex("""\${'$'}\{([^}]*)}|\${'$'}(\w+)""")

        /** Group 1 is the keyword, 2 the name, 3 a receiver dot — an extension, which this scan does not judge. */
        val TOP_LEVEL_DECLARATION = Regex(
            """^(?:@\w+(?:\([^)]*\))?\s+)*""" +
                """(?:internal |public |abstract |open |sealed |data |value |enum |annotation |inline |const )*""" +
                """(class|object|interface|fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)(\.?)""",
        )

        /** The same shape one level in: a member of a top-level object. Group 2 is again the receiver dot. */
        val MEMBER_DECLARATION = Regex(
            """^ {4}(?:@\w+(?:\([^)]*\))?\s+)*""" +
                """(?:internal |public |open |const |inline |suspend |operator |infix )*""" +
                """(?:fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)(\.?)""",
        )

        /** `private` is detekt's business; an `override` is reached through the supertype, not by its own name. */
        val SKIPPED_MODIFIER = Regex("""\b(private|override)\s""")
    }
}
