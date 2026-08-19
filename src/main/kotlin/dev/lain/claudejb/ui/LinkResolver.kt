package dev.lain.claudejb.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.lain.claudejb.diff.DiffPresenter
import java.io.File

object LinkResolver {

    data class Resolved(val token: String, val path: String, val line: Int?)

    private const val MAX_PATHS = 60
    private const val MAX_SYMBOLS = 40

    private const val MAX_SCAN_ENTRIES = 20_000

    private val SKIP_DIRS = setOf("node_modules", "target", "out", "venv", "__pycache__")

    fun isOpenable(path: String?, projectRoot: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return DiffPresenter.isWithinRoot(path, projectRoot) || DiffPresenter.isWithinRoot(path, userHome())
    }

    fun userHome(): String? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }

    private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]+:")

    fun isFilePathHref(href: String): Boolean {
        val h = href.trim()
        return h.isNotEmpty() && !HAS_SCHEME.containsMatchIn(h)
    }

    fun expandHome(raw: String): String {
        if (raw != "~" && !raw.startsWith("~/")) return raw
        val home = userHome() ?: return raw
        return if (raw == "~") home else File(home, raw.removePrefix("~/")).path
    }

    fun resolvePaths(project: Project, candidates: List<String>): List<Resolved> {
        val root = project.basePath
        val outcomes = candidates.take(MAX_PATHS).mapNotNull { resolveOnePath(it, root) }
        val onDisk = outcomes.filterIsInstance<PathOutcome.Direct>().map { it.resolved }
        val bareNames = outcomes.filterIsInstance<PathOutcome.BareName>().map { it.name to it.line }
        return onDisk + resolveByName(project, bareNames)
    }

    private sealed interface PathOutcome {
        data class Direct(val resolved: Resolved) : PathOutcome
        data class BareName(val name: String, val line: Int?) : PathOutcome
    }

    private fun resolveOnePath(raw: String, root: String?): PathOutcome? {
        val (pathPart, line) = splitLine(raw)
        if (pathPart.isBlank()) return null
        val expanded = expandHome(pathPart)
        val abs = when {
            File(expanded).isAbsolute -> File(expanded)
            root != null -> File(root, expanded)
            else -> return null
        }
        if (!abs.exists()) {
            val isBareName = !pathPart.contains('/') && !pathPart.contains('\\')
            return if (isBareName) PathOutcome.BareName(pathPart, line) else null
        }
        if (!isOpenable(abs.path, root)) return null
        return PathOutcome.Direct(Resolved(raw, displayPath(abs.path, root), line))
    }

    private fun resolveByName(project: Project, names: List<Pair<String, Int?>>): List<Resolved> {
        if (names.isEmpty()) return emptyList()
        val root = project.basePath
        val unresolved = ArrayList<Pair<String, Int?>>()
        val indexed = try {
            ReadAction.nonBlocking<List<Resolved>> {
                names.take(MAX_PATHS).mapNotNull { (name, line) ->
                    when (val hit = lookUpName(project, name, line, root)) {
                        is NameLookup.Found -> hit.resolved

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

    private sealed interface NameLookup {
        data class Found(val resolved: Resolved) : NameLookup

        object NotIndexed : NameLookup

        object NoLink : NameLookup
    }

    private fun lookUpName(project: Project, name: String, line: Int?, root: String?): NameLookup {
        val hits = runCatching {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        }.getOrNull().orEmpty()
        if (hits.size > 1) return NameLookup.NoLink
        val vf = hits.firstOrNull() ?: return NameLookup.NotIndexed
        if (vf.isDirectory || !isOpenable(vf.path, root)) return NameLookup.NoLink
        val token = if (line != null) "$name:$line" else name
        return NameLookup.Found(Resolved(token, displayPath(vf.path, root), line))
    }

    fun scanForNames(root: String?, names: List<Pair<String, Int?>>): List<Resolved> {
        if (root == null || names.isEmpty()) return emptyList()
        val lineOf = names.toMap()
        val hits = scanTree(File(root), lineOf.keys)
        return hits.mapNotNull { (name, files) ->
            val file = files.singleOrNull() ?: return@mapNotNull null
            if (!isOpenable(file.path, root)) return@mapNotNull null
            val line = lineOf[name]
            Resolved(if (line != null) "$name:$line" else name, displayPath(file.path, root), line)
        }
    }

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

    private fun isWorthDescending(dir: File): Boolean = dir.name !in SKIP_DIRS && !dir.name.startsWith(".")

    fun resolveSymbols(project: Project, candidates: List<String>): List<Resolved> {
        val root = project.basePath ?: return emptyList()
        val names = candidates.take(MAX_SYMBOLS)
        if (names.isEmpty()) return emptyList()
        return try {
            ReadAction.nonBlocking<List<Resolved>> {
                names.mapNotNull { resolveSymbolOrNull(project, it, root) }
            }.inSmartMode(project).expireWith(project).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            emptyList()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveSymbolOrNull(project: Project, name: String, root: String): Resolved? = try {
        resolveOneSymbol(project, name, root)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        Logger.getInstance(LinkResolver::class.java).debug("could not resolve the symbol '$name' to a link", e)
        null
    }

    private fun resolveOneSymbol(project: Project, name: String, root: String): Resolved? {
        val hits = itemsFor(project, name)
        if (hits.size != 1) return null
        val psi = hits.first() as? PsiElement ?: return null
        if (!psi.isValid) return null
        val vf = psi.containingFile?.virtualFile?.takeIf { it.isValid } ?: return null
        if (!isOpenable(vf.path, root)) return null
        return Resolved(name, displayPath(vf.path, root), lineOf(psi))
    }

    private fun itemsFor(project: Project, name: String): List<NavigationItem> {
        val hits = LinkedHashSet<NavigationItem>()
        for (contributor in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
            val items = runCatching {
                contributor.getItemsByName(name, name, project, false)
            }.getOrNull() ?: continue
            items.filterNotNullTo(hits)
            if (hits.size > 1) return hits.toList()
        }
        return hits.toList()
    }

    private fun lineOf(psi: PsiElement): Int? {
        val file = psi.containingFile ?: return null
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(psi.project).getDocument(file) ?: return null
        val offset = psi.textOffset
        if (offset < 0 || offset > doc.textLength) return null
        return doc.getLineNumber(offset) + 1
    }

    private fun splitLine(raw: String): Pair<String, Int?> {
        val i = raw.lastIndexOf(':')
        if (i <= 0) return raw to null
        val line = raw.substring(i + 1).toIntOrNull() ?: return raw to null
        return raw.substring(0, i) to line.coerceAtLeast(1)
    }

    fun displayPath(absPath: String, root: String?): String = relativize(absPath, root) ?: absPath

    private fun relativize(path: String, root: String?): String? {
        if (root.isNullOrBlank()) return null
        val p = path.replace('\\', '/')
        val r = root.trimEnd('/', '\\').replace('\\', '/')
        if (!p.startsWith("$r/")) return null
        return p.removePrefix("$r/")
    }
}
