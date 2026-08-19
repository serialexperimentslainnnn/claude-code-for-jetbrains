package dev.lain.claudejb.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object FilePickerHelper {

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

    fun displayName(project: Project, path: String): String = displayName(project.basePath, path)

    fun relativeWithinRoot(basePath: String?, path: String): String? {
        if (basePath == null) return null
        return runCatching { File(basePath).toPath().relativize(File(path).toPath()).toString() }
            .getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") }?.replace('\\', '/')
    }

    fun openFiles(project: Project): List<String> =
        FileEditorManager.getInstance(project).openFiles.map { it.path }

    fun openFiles(project: Project, paths: List<String>) {
        val fs = LocalFileSystem.getInstance()
        val manager = FileEditorManager.getInstance(project)
        for (path in paths) {
            val vf = fs.findFileByPath(path) ?: continue
            manager.openFile(vf, true)
        }
    }

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
