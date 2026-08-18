package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Composer-side helper for attaching and opening files: project-root-relative labels plus the IDE editor
 * and recent-files plumbing the Attach menu needs. The label logic ([displayName]) is promoted out of
 * `ExplainSelectionAction.relativize` so it can be unit-tested in isolation (the action keeps its own private
 * copy working); everything else is a thin, non-deprecated wrapper over the platform
 * `FileEditorManager`/`EditorHistoryManager`.
 *
 * **No platform file chooser lives here, and that is a rule rather than an omission.** Files and directories
 * are picked from [ProjectTree], the attach menu's in-page project browser, where every entry passes the same
 * canonicalize-and-prefix gate that confines what the binary may write. A `FileChooser` can be walked anywhere
 * on disk from wherever it is rooted, so re-adding one would put a second, weaker answer beside that gate —
 * and the attach menu's contract is that it offers what is inside the project.
 */
object FilePickerHelper {

    /**
     * Project-root-relative display label for [path]. PURE and string-based (no canonicalization):
     * when [path] is under [basePath] the leading base + separators are stripped to a clean relative
     * label (e.g. `src/Foo.kt`); otherwise it falls back to the bare file name, or the original
     * absolute path when there is no separator to peel. Mirrors `ExplainSelectionAction.relativize`.
     */
    fun displayName(basePath: String?, path: String): String {
        if (basePath != null) {
            val base = basePath.trimEnd('/')
            if (path == base) return path.substringAfterLast('/').ifEmpty { path }
            if (path.startsWith("$base/")) {
                return path.removePrefix(base).trimStart('/')
            }
        }
        val name = path.substringAfterLast('/')
        return name.ifEmpty { path }
    }

    /** [displayName] overload keyed off the project's root ([Project.getBasePath]). */
    fun displayName(project: Project, path: String): String = displayName(project.basePath, path)

    /**
     * The forward-slash path of [path] relative to [basePath], or null when [path] is outside the root (a `..`
     * result), equals the root, or can't be relativized. **Path-based** (normalizes `.`/`..`) — the single source
     * of truth for the `@`-mention wire form and the rollback list's display path. (For the pure, string-only chip
     * label that falls back to a bare file name, use [displayName].)
     */
    fun relativeWithinRoot(basePath: String?, path: String): String? {
        if (basePath == null) return null
        return runCatching { File(basePath).toPath().relativize(File(path).toPath()).toString() }
            .getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") }?.replace('\\', '/')
    }

    /** Absolute paths of the files currently open in editors (for an "Add open files…" menu). EDT. */
    fun openFiles(project: Project): List<String> =
        FileEditorManager.getInstance(project).openFiles.map { it.path }

    /** Open each resolvable path in an editor tab (requesting focus); unresolved paths are skipped. */
    fun openFiles(project: Project, paths: List<String>) {
        val fs = LocalFileSystem.getInstance()
        val manager = FileEditorManager.getInstance(project)
        for (path in paths) {
            val vf = fs.findFileByPath(path) ?: continue
            manager.openFile(vf, true)
        }
    }

    /**
     * Up to [limit] recently opened files (newest-first) from the IDE's editor history, dropping any
     * that no longer exist on disk. Returns paths suitable for [openFiles]/attachment chips.
     */
    fun recentFiles(project: Project, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        return EditorHistoryManager.getInstance(project).fileList
            .asReversed()
            .asSequence()
            .filter { it.isValid && it.exists() }
            .take(limit)
            .map { it.path }
            .toList()
    }
}
