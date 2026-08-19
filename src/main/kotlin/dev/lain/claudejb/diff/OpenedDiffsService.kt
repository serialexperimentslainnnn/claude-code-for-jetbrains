package dev.lain.claudejb.diff

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArraySet

@Service(Service.Level.PROJECT)
class OpenedDiffsService(private val project: Project) {

    private val files = CopyOnWriteArraySet<VirtualFile>()

    fun register(file: VirtualFile) {
        files.add(file)
    }

    fun unregister(file: VirtualFile) {
        files.remove(file)
    }

    fun closeAll() {
        val manager = FileEditorManager.getInstance(project)
        val snapshot = files.toList()
        files.clear()
        snapshot.forEach { if (manager.isFileOpen(it)) manager.closeFile(it) }
    }

    companion object {
        fun getInstance(project: Project): OpenedDiffsService = project.service()
    }
}
