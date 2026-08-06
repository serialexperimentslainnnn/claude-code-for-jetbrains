package dev.lain.claudejb.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.lain.claudejb.diff.DiffPresenter
import java.io.File

/**
 * Resolves the jump-to-code candidates the transcript detects in model text — **file paths** and **symbol names**
 * (functions/classes) — into something the editor can actually open.
 *
 * The transcript can only *guess* ("`PermissionBroker`" might be a class, or just a word). So the frontend sends a
 * batch of candidates and this decides which are real; only the resolved ones become links. That way a path that
 * doesn't exist, or a word that isn't a symbol, is never rendered as a dead hyperlink.
 *
 * A path candidate is tried, in order, as: a path relative to the project root (or absolute, or `~/…`) → a bare
 * file name in the project's file index (`app.css:190` is how a developer cites a file) → a bare file name found by
 * a bounded on-disk scan (an *excluded* dir like `build/` is in no index, yet `foo.zip` is worth revealing). At
 * every stage the rule is the same as for symbols: **only an unambiguous match links** — two `app.css` in the tree
 * means no link at all, rather than a jump to an arbitrary one.
 *
 * **Language-agnostic on purpose.** Symbols go through the [ChooseByNameContributor.SYMBOL_EP_NAME] extension
 * point — the same index behind *Go to Symbol* — which every language plugin contributes to. We deliberately do
 * NOT touch Java PSI (`PsiShortNamesCache`): that would tie the plugin to the Java plugin and break it in
 * PyCharm/WebStorm/etc., which the plugin supports.
 *
 * **Security:** every resolved path is gated by [isOpenable] — a link can only ever point inside the project or
 * inside the user's own home, never at `/etc/passwd` or another user's files, not even via a symlink. Symbols are
 * resolved in project scope only (`includeNonProjectItems = false`), so library/SDK declarations are not offered.
 */
object LinkResolver {

    /**
     * A candidate that resolved: the token as written in the text, plus where to jump. [path] is **relative to the
     * project root** when the target is inside it (the common case, and what the transcript shows), and absolute
     * when it isn't (a file in the user's home) — there is nothing to relativise it against.
     */
    data class Resolved(val token: String, val path: String, val line: Int?)

    /** Caps: the transcript can throw a lot of tokens at us; resolution is indexed but not free. */
    private const val MAX_PATHS = 60
    private const val MAX_SYMBOLS = 40

    /** Ceiling for the on-disk fallback scan ([scanForNames]) — it must never turn into a full-tree crawl. */
    private const val MAX_SCAN_ENTRIES = 20_000

    /** Directories the fallback scan never descends into: huge, and nothing in them is worth linking. */
    private val SKIP_DIRS = setOf("node_modules", "target", "out", "venv", "__pycache__")

    /**
     * Where a jump-to-code link is allowed to point: **inside the project, or inside the user's own home**.
     *
     * Deliberately wider than the write gate ([DiffPresenter.isWithinRoot] alone, which confines what the binary
     * may *write* to the project tree): opening a file is a read the user explicitly clicks, and the link always
     * renders the resolved path as its own text, so nothing can be disguised. It is still a gate, not a free pass —
     * `/etc/passwd`, `/usr/...` and another user's home stay unreachable, and because [DiffPresenter.isWithinRoot]
     * compares **canonical** paths, a symlink planted inside the home that points at `/etc` does not escape either.
     */
    fun isOpenable(path: String?, projectRoot: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return DiffPresenter.isWithinRoot(path, projectRoot) || DiffPresenter.isWithinRoot(path, userHome())
    }

    /** The user's home, or null when the JVM doesn't report one (then only the project root is openable). */
    fun userHome(): String? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }

    /** `~/notes/x.md` → an absolute path under the user's home. Anything else is returned unchanged. */
    // A URI scheme prefix (`https:`, `mailto:`, `jb:`…), used to tell a link's href apart from a file path.
    // Two-or-more characters before the colon on purpose: a single letter is a WINDOWS DRIVE (`C:\src`), which
    // is a path, not a scheme. This plugin ships on Windows, so getting that backwards would break every
    // absolute-path link there.
    private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]+:")

    /**
     * True when a link's href is a FILE PATH rather than a URL — that is, it carries no URI scheme.
     *
     * Markdown links written by the model use plain relative paths (`[BACKLOG](docs/BACKLOG.md)`). Those matched
     * no scheme branch in the host's link handler and were silently dropped, so the most deliberate kind of link
     * was the only one that did nothing. Bare paths in prose already worked, which is what made it confusing.
     */
    fun isFilePathHref(href: String): Boolean {
        val h = href.trim()
        return h.isNotEmpty() && !HAS_SCHEME.containsMatchIn(h)
    }

    fun expandHome(raw: String): String {
        if (raw != "~" && !raw.startsWith("~/")) return raw
        val home = userHome() ?: return raw
        return if (raw == "~") home else File(home, raw.removePrefix("~/")).path
    }

    /**
     * Resolves path candidates — **files and directories alike**. A candidate is kept only when it names something
     * that really exists on disk and passes [isOpenable]. Accepts a path relative to the project root
     * (`src/Foo.kt`), an absolute one, or a `~/…` one; the result is relative when it lands inside the project and
     * absolute otherwise.
     */
    fun resolvePaths(project: Project, candidates: List<String>): List<Resolved> {
        val root = project.basePath
        val outcomes = candidates.take(MAX_PATHS).mapNotNull { resolveOnePath(it, root) }
        val onDisk = outcomes.filterIsInstance<PathOutcome.Direct>().map { it.resolved }
        val bareNames = outcomes.filterIsInstance<PathOutcome.BareName>().map { it.name to it.line }
        return onDisk + resolveByName(project, bareNames)
    }

    /** What one path candidate turned out to be: a real path, a bare name to look up later, or nothing. */
    private sealed interface PathOutcome {
        data class Direct(val resolved: Resolved) : PathOutcome
        data class BareName(val name: String, val line: Int?) : PathOutcome
    }

    /** Resolves ONE candidate. Null means "not a link" — never a dead link; the token stays plain text. */
    private fun resolveOnePath(raw: String, root: String?): PathOutcome? {
        val (pathPart, line) = splitLine(raw)
        if (pathPart.isBlank()) return null
        val expanded = expandHome(pathPart)
        val abs = when {
            File(expanded).isAbsolute -> File(expanded)
            root != null -> File(root, expanded)
            else -> return null // a relative path with no project to resolve it against
        }
        if (!abs.exists()) {
            // Not a path relative to the root — but a BARE FILE NAME (`app.css:190`, `JcefHost.kt`) is how a
            // developer normally cites a file, so give it a second chance against the project's file index.
            val isBareName = !pathPart.contains('/') && !pathPart.contains('\\')
            return if (isBareName) PathOutcome.BareName(pathPart, line) else null
        }
        if (!isOpenable(abs.path, root)) return null // project or home only
        return PathOutcome.Direct(Resolved(raw, displayPath(abs.path, root), line))
    }

    /**
     * Second chance for bare file names: resolve them through the project's **file-name index** (the one behind
     * *Go to File*), exactly as [resolveSymbols] does for symbols — and with the same rule: **only an unambiguous
     * match links**. Two files named `app.css` means no link at all, rather than a jump to an arbitrary one.
     *
     * **Must be called off the EDT** (index access, inside a cancellable read action that waits for smart mode).
     */
    private fun resolveByName(project: Project, names: List<Pair<String, Int?>>): List<Resolved> {
        if (names.isEmpty()) return emptyList()
        val root = project.basePath
        val unresolved = ArrayList<Pair<String, Int?>>()
        val indexed = try {
            ReadAction.nonBlocking<List<Resolved>> {
                names.take(MAX_PATHS).mapNotNull { (name, line) ->
                    when (val hit = lookUpName(project, name, line, root)) {
                        is NameLookup.Found -> hit.resolved

                        // Not in the index at all — it may live in an EXCLUDED dir, so the disk scan gets a turn.
                        NameLookup.NotIndexed -> {
                            unresolved += name to line
                            null
                        }

                        NameLookup.NoLink -> null
                    }
                }
            }.inSmartMode(project).expireWith(project).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            emptyList()
        }
        return indexed + scanForNames(root, unresolved)
    }

    /** The outcome of one file-name-index lookup. */
    private sealed interface NameLookup {
        data class Found(val resolved: Resolved) : NameLookup

        /** The index has never heard of it — worth a disk scan (excluded dirs are not indexed). */
        object NotIndexed : NameLookup

        /** Ambiguous, a directory, or outside the openable roots: deliberately no link. */
        object NoLink : NameLookup
    }

    private fun lookUpName(project: Project, name: String, line: Int?, root: String?): NameLookup {
        val hits = runCatching {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        }.getOrNull().orEmpty()
        if (hits.size > 1) return NameLookup.NoLink // ambiguous → don't guess
        val vf = hits.firstOrNull() ?: return NameLookup.NotIndexed
        if (vf.isDirectory || !isOpenable(vf.path, root)) return NameLookup.NoLink
        val token = if (line != null) "$name:$line" else name
        return NameLookup.Found(Resolved(token, displayPath(vf.path, root), line))
    }

    /**
     * Last resort for a bare name the index does not know: **excluded** folders (a build-output dir like `build/`)
     * are not indexed at all, yet `claude-code-native-4.3.0.zip` is a perfectly good thing to link — you click it
     * to reveal it in the tree. So walk the project tree on disk once, for every still-unresolved name at once.
     *
     * Bounded on purpose: one breadth-first pass, at most [MAX_SCAN_ENTRIES] entries, skipping the directories
     * that would blow the budget without ever holding an interesting file (`.git`, `node_modules`, dot-dirs). The
     * unambiguous-only rule still holds — two matches, no link.
     *
     * PURE (filesystem only, no IDE): takes the root as a string, so it is directly unit-testable.
     */
    fun scanForNames(root: String?, names: List<Pair<String, Int?>>): List<Resolved> {
        if (root == null || names.isEmpty()) return emptyList()
        val lineOf = names.toMap()
        val hits = scanTree(File(root), lineOf.keys)
        return hits.mapNotNull { (name, files) ->
            val file = files.singleOrNull() ?: return@mapNotNull null // ambiguous → no link
            if (!isOpenable(file.path, root)) return@mapNotNull null
            val line = lineOf[name]
            Resolved(if (line != null) "$name:$line" else name, displayPath(file.path, root), line)
        }
    }

    /**
     * One bounded breadth-first pass under [root], collecting every file whose name is in [wanted]. Returns all
     * matches per name — deciding what to do with two of them is [scanForNames]'s call, not this one's.
     */
    private fun scanTree(root: File, wanted: Set<String>): Map<String, List<File>> {
        val hits = HashMap<String, MutableList<File>>()
        val queue = ArrayDeque<File>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SCAN_ENTRIES) {
            val children = queue.removeFirst().listFiles() ?: continue
            for (child in children) {
                seen++
                visit(child, wanted, queue, hits)
            }
        }
        return hits
    }

    /** Enqueues [child] if it is a directory worth descending, or records it if its name is [wanted]. */
    private fun visit(
        child: File,
        wanted: Set<String>,
        queue: ArrayDeque<File>,
        hits: MutableMap<String, MutableList<File>>,
    ) {
        if (child.isDirectory) {
            if (isWorthDescending(child)) queue.addLast(child)
            return
        }
        if (child.name in wanted) hits.getOrPut(child.name) { ArrayList() }.add(child)
    }

    /** Skips the directories that would blow the entry budget without ever holding an interesting file. */
    private fun isWorthDescending(dir: File): Boolean = dir.name !in SKIP_DIRS && !dir.name.startsWith(".")

    /**
     * Resolves symbol-name candidates (a function, class, …) to their declaration site via the *Go to Symbol*
     * index. Only **unambiguous** matches are returned: if a name resolves to several declarations we skip it
     * rather than send the user to an arbitrary one.
     *
     * **Must be called off the EDT.** It runs as a cancellable non-blocking read action, waiting for smart mode —
     * the Go-to-Symbol index simply does not exist while the IDE is indexing, so asking during dumb mode would
     * either throw or answer "no symbol" for names that do exist. A cancellation (the project closes, a write
     * action preempts us) yields no links rather than an error: the row keeps its plain text.
     */
    fun resolveSymbols(project: Project, candidates: List<String>): List<Resolved> {
        val root = project.basePath ?: return emptyList()
        val names = candidates.take(MAX_SYMBOLS)
        if (names.isEmpty()) return emptyList()
        return try {
            ReadAction.nonBlocking<List<Resolved>> {
                names.mapNotNull { resolveOneSymbol(project, it, root) }
            }.inSmartMode(project).expireWith(project).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            emptyList()
        }
    }

    /** One symbol → its declaration site, or null. Ambiguous never links: a wrong jump is worse than no link. */
    private fun resolveOneSymbol(project: Project, name: String, root: String): Resolved? {
        val hits = itemsFor(project, name)
        if (hits.size != 1) return null
        val psi = hits.first() as? PsiElement ?: return null
        val vf = psi.containingFile?.virtualFile ?: return null
        if (!isOpenable(vf.path, root)) return null
        return Resolved(name, displayPath(vf.path, root), lineOf(psi))
    }

    /** All project-scoped declarations named [name], across every language that contributes to Go-to-Symbol. */
    private fun itemsFor(project: Project, name: String): List<NavigationItem> {
        val hits = LinkedHashSet<NavigationItem>()
        for (contributor in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
            // A misbehaving language contributor must not break the whole transcript.
            // getItemsByName(name, pattern, project, includeNonProjectItems) — a Java API, so the trailing
            // `false` cannot be named: we want PROJECT symbols only, never library or SDK declarations.
            val items = runCatching {
                contributor.getItemsByName(name, name, project, false)
            }.getOrNull() ?: continue
            items.filterNotNullTo(hits)
            if (hits.size > 1) return hits.toList() // already ambiguous — stop early
        }
        return hits.toList()
    }

    /** 1-based line of [psi] in its file, or null when it can't be determined. */
    private fun lineOf(psi: PsiElement): Int? {
        val file = psi.containingFile ?: return null
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(psi.project).getDocument(file) ?: return null
        val offset = psi.textOffset
        if (offset < 0 || offset > doc.textLength) return null
        return doc.getLineNumber(offset) + 1
    }

    /** `src/Foo.kt:42` → (`src/Foo.kt`, 42). No suffix → (path, null). */
    private fun splitLine(raw: String): Pair<String, Int?> {
        val i = raw.lastIndexOf(':')
        if (i <= 0) return raw to null
        val line = raw.substring(i + 1).toIntOrNull() ?: return raw to null
        return raw.substring(0, i) to line.coerceAtLeast(1)
    }

    /**
     * How the link is written out: relative to the project when it lands inside it (short, and what the transcript
     * already shows), absolute when it doesn't (a file in the home — there is no root to relativise it against).
     */
    fun displayPath(absPath: String, root: String?): String = relativize(absPath, root) ?: absPath

    /** Absolute path → path relative to [root], or null when it isn't under it. */
    private fun relativize(path: String, root: String?): String? {
        if (root.isNullOrBlank()) return null
        val p = path.replace('\\', '/')
        val r = root.trimEnd('/', '\\').replace('\\', '/')
        if (!p.startsWith("$r/")) return null
        return p.removePrefix("$r/")
    }
}
