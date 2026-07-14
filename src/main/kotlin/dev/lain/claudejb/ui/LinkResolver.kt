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
        val out = ArrayList<Resolved>()
        val byName = ArrayList<Pair<String, Int?>>()
        for (raw in candidates.take(MAX_PATHS)) {
            val (pathPart, line) = splitLine(raw)
            if (pathPart.isBlank()) continue
            val expanded = expandHome(pathPart)
            val abs = when {
                File(expanded).isAbsolute -> File(expanded)
                root != null -> File(root, expanded)
                else -> continue // a relative path with no project to resolve it against
            }
            if (!abs.exists()) {
                // Not a path relative to the root — but a BARE FILE NAME (`app.css:190`, `JcefHost.kt`) is how a
                // developer normally cites a file, so give it a second chance against the project's file index.
                if (!pathPart.contains('/') && !pathPart.contains('\\')) byName += pathPart to line
                continue // never a dead link: if the index doesn't know it either, it stays plain text
            }
            if (!isOpenable(abs.path, root)) continue    // project or home only
            out.add(Resolved(raw, displayPath(abs.path, root), line))
        }
        out += resolveByName(project, byName)
        return out
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
                val out = ArrayList<Resolved>()
                for ((name, line) in names.take(MAX_PATHS)) {
                    val hits = runCatching {
                        FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
                    }.getOrNull().orEmpty()
                    if (hits.size > 1) continue                    // ambiguous → don't guess
                    if (hits.isEmpty()) { unresolved += name to line; continue } // maybe an EXCLUDED dir — see below
                    val vf = hits.first()
                    if (vf.isDirectory || !isOpenable(vf.path, root)) continue
                    val token = if (line != null) "$name:$line" else name
                    out.add(Resolved(token, displayPath(vf.path, root), line))
                }
                out
            }.inSmartMode(project).expireWith(project).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            emptyList()
        }
        return indexed + scanForNames(root, unresolved)
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
        val hits = HashMap<String, MutableList<File>>()
        var seen = 0
        val queue = ArrayDeque<File>().apply { add(File(root)) }
        while (queue.isNotEmpty() && seen < MAX_SCAN_ENTRIES) {
            val children = queue.removeFirst().listFiles() ?: continue
            for (child in children) {
                seen++
                if (child.isDirectory) {
                    if (child.name !in SKIP_DIRS && !child.name.startsWith(".")) queue.addLast(child)
                } else if (child.name in lineOf) {
                    hits.getOrPut(child.name) { ArrayList() }.add(child)
                }
            }
        }
        return hits.mapNotNull { (name, files) ->
            val file = files.singleOrNull() ?: return@mapNotNull null   // ambiguous → no link
            if (!isOpenable(file.path, root)) return@mapNotNull null
            val line = lineOf[name]
            Resolved(if (line != null) "$name:$line" else name, displayPath(file.path, root), line)
        }
    }

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
                val out = ArrayList<Resolved>()
                for (name in names) {
                    val hits = itemsFor(project, name)
                    // Ambiguous (or nothing): don't guess. A wrong jump is worse than no link.
                    if (hits.size != 1) continue
                    val psi = hits.first() as? PsiElement ?: continue
                    val vf = psi.containingFile?.virtualFile ?: continue
                    if (!isOpenable(vf.path, root)) continue
                    out.add(Resolved(name, displayPath(vf.path, root), lineOf(psi)))
                }
                out
            }.inSmartMode(project).expireWith(project).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            emptyList()
        }
    }

    /** All project-scoped declarations named [name], across every language that contributes to Go-to-Symbol. */
    private fun itemsFor(project: Project, name: String): List<NavigationItem> {
        val hits = LinkedHashSet<NavigationItem>()
        for (contributor in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
            // A misbehaving language contributor must not break the whole transcript.
            val items = runCatching {
                contributor.getItemsByName(name, name, project, /* includeNonProjectItems = */ false)
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
