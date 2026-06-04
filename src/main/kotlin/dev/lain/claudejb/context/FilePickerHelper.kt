package dev.lain.claudejb.context

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Composer-side helper for attaching/opening files: project-root-relative labels plus the
 * IDE file-choosing, opening and recent-files plumbing the composer needs. The label logic
 * ([displayName]) is promoted out of `ExplainSelectionAction.relativize` so it can be unit-tested
 * in isolation (the action keeps its own private copy working); everything else is a thin,
 * non-deprecated wrapper over the platform `FileChooser`/`FileEditorManager`/`EditorHistoryManager`.
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

    /**
     * Show a multi-file picker (no jars) rooted at the project and return the chosen file paths;
     * empty when the user cancels.
     */
    fun chooseFiles(project: Project): List<String> {
        val descriptor = FileChooserDescriptorFactory.multiFiles()
        return FileChooser.chooseFiles(descriptor, project, null).map { it.path }
    }

    /** Show a single-folder picker rooted at the project and return its path, or null on cancel. */
    fun chooseDirectory(project: Project): String? {
        val descriptor = FileChooserDescriptorFactory.singleDir()
        return FileChooser.chooseFile(descriptor, project, null)?.path
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
