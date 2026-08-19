package dev.lain.claudejb.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.diff.DiffPresenter
import java.io.File

internal object ProjectTree {

    enum class Mode { FILES, DIRECTORIES }

    data class Entry(val name: String, val path: String, val directory: Boolean)

    data class Expansion(val paths: List<String>, val truncated: Boolean)

    const val MAX_ENTRIES: Int = 500

    private const val MAX_VISITED = 20_000

    private const val MAX_FILE_BYTES = ImageAttachments.MAX_IMAGE_BYTES

    private val NOTHING = Expansion(emptyList(), false)

    fun children(project: Project, path: String, mode: Mode): List<Entry> =
        inReadAction(project, emptyList()) { listing(project, path, mode) }

    fun expand(project: Project, path: String, mode: Mode): Expansion =
        inReadAction(project, NOTHING) { expansion(project, path, mode) }

    fun resolve(root: String?, relativePath: String): File? {
        if (root.isNullOrBlank()) return null
        val candidate = runCatching { File(root, relativePath).toPath().normalize().toFile() }.getOrNull()
        return candidate?.takeIf { DiffPresenter.isWithinRoot(it.path, root) }
    }

    fun ordered(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }, { it.name }))

    fun isAttachableFile(name: String, sizeBytes: Long, binary: Boolean): Boolean {
        if (sizeBytes > MAX_FILE_BYTES) return false
        if (!binary) return true
        return ImageAttachments.mediaTypeForExtension(name.substringAfterLast('.', "").lowercase()) != null
    }

    fun walk(start: Entry, mode: Mode, childrenOf: (Entry) -> List<Entry>): Expansion {
        val wantsDirectories = mode == Mode.DIRECTORIES
        val collected = ArrayList<String>()
        if (wantsDirectories) collected += start.path
        val queue = ArrayDeque<Entry>().apply { add(start) }
        var visited = 0
        while (queue.isNotEmpty()) {
            for (child in childrenOf(queue.removeFirst())) {
                if (++visited > MAX_VISITED) return Expansion(collected, true)
                val wanted = child.directory == wantsDirectories
                if (wanted && collected.size >= MAX_ENTRIES) return Expansion(collected, true)
                if (wanted) collected += child.path
                if (child.directory) queue.addLast(child)
            }
        }
        return Expansion(collected, false)
    }

    private fun listing(project: Project, path: String, mode: Mode): List<Entry> {
        val root = project.basePath ?: return emptyList()
        val dir = directoryAt(root, path) ?: return emptyList()
        return ordered(visibleChildren(ProjectFileIndex.getInstance(project), root, dir, mode))
    }

    private fun expansion(project: Project, path: String, mode: Mode): Expansion {
        val root = project.basePath ?: return NOTHING
        val startDir = directoryAt(root, path) ?: return NOTHING
        val index = ProjectFileIndex.getInstance(project)
        val start = Entry(startDir.name, FilePickerHelper.relativeWithinRoot(root, startDir.path).orEmpty(), true)
        return walk(start, mode) { entry ->
            val dir = if (entry == start) startDir else descendable(root, entry.path)
            if (dir == null) emptyList() else visibleChildren(index, root, dir, mode)
        }
    }

    private fun visibleChildren(index: ProjectFileIndex, root: String, dir: VirtualFile, mode: Mode): List<Entry> {
        val kids: Array<VirtualFile> = dir.children ?: return emptyList()
        return kids.asSequence()
            .filterNot { index.isExcluded(it) }
            .mapNotNull { entryFor(root, it, mode) }
            .take(MAX_ENTRIES)
            .toList()
    }

    private fun entryFor(root: String, vf: VirtualFile, mode: Mode): Entry? {
        if (!DiffPresenter.isWithinRoot(vf.path, root)) return null
        val relative = FilePickerHelper.relativeWithinRoot(root, vf.path) ?: return null
        if (vf.isDirectory) return Entry(vf.name, relative, true)
        if (mode == Mode.DIRECTORIES) return null
        if (!isAttachableFile(vf.name, vf.length, isBinary(vf.name))) return null
        return Entry(vf.name, relative, false)
    }

    private fun descendable(root: String, relative: String): VirtualFile? =
        directoryAt(root, relative)?.takeUnless { it.`is`(VFileProperty.SYMLINK) }

    private fun directoryAt(root: String, relative: String): VirtualFile? {
        val dir = resolve(root, relative) ?: return null
        return LocalFileSystem.getInstance().findFileByIoFile(dir)?.takeIf { it.isValid && it.isDirectory }
    }

    private fun isBinary(name: String): Boolean {
        val type = FileTypeRegistry.getInstance().getFileTypeByFileName(name)
        return type != UnknownFileType.INSTANCE && type.isBinary
    }

    private fun <T> inReadAction(project: Project, fallback: T, body: () -> T): T = try {
        ReadAction.nonBlocking<T> { body() }.expireWith(project).executeSynchronously()
    } catch (_: ProcessCanceledException) {
        fallback
    }
}
