package dev.lain.claudejb.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.EditSnapshotStore
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DiffLifecycleManager(private val project: Project) {

    private val editSnapshots = EditSnapshotStore()

    private val autoOpenedDiffs = ConcurrentHashMap<String, VirtualFile>()

    private val pendingRefresh = java.util.Collections.synchronizedSet(HashSet<String>())

    private val reviewDiffs = ConcurrentHashMap<String, DiffPresenter.ReviewDiff>()

    fun openReviewDiff(requestId: String, toolName: String, input: JsonObject) {
        if (reviewDiffs.containsKey(requestId)) return
        DiffPresenter.openReviewDiff(project, toolName, input)?.let { reviewDiffs[requestId] = it }
    }

    fun takeReviewEdit(requestId: String): Pair<String, String>? {
        val rd = reviewDiffs.remove(requestId) ?: return null
        val edited = runCatching { rd.proposed.text }.getOrNull()
        DiffPresenter.closeDiff(project, rd.file)
        return if (edited != null && edited != rd.originalProposed) rd.currentText to edited else null
    }

    fun closeReviewDiff(requestId: String) {
        reviewDiffs.remove(requestId)?.let { DiffPresenter.closeDiff(project, it.file) }
    }

    fun clearReviewDiffs() {
        reviewDiffs.keys.toList().forEach { closeReviewDiff(it) }
    }

    fun captureForReview(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? =
        editSnapshots.capture(toolName, input, toolUseId)

    fun autoOpenDiff(toolName: String, input: JsonObject, toolUseId: String) {
        val snapshot = editSnapshots.capture(toolName, input, toolUseId) ?: return
        edt {
            val file = DiffPresenter.openDiff(project, toolName, input, snapshot.beforeText) ?: return@edt
            autoOpenedDiffs[toolUseId] = file
        }
    }

    fun onToolResult(toolUseId: String): EditSnapshot? {
        autoOpenedDiffs.remove(toolUseId)?.let { file -> edt { DiffPresenter.closeDiff(project, file) } }
        return editSnapshots.get(toolUseId)
    }

    fun snapshot(toolUseId: String): EditSnapshot? = editSnapshots.get(toolUseId)

    fun updateSnapshotInput(toolUseId: String, input: JsonObject) = editSnapshots.updateInput(toolUseId, input)

    fun markForRefresh(path: String) {
        pendingRefresh.add(path)
    }

    fun refreshTouched() {
        val paths = synchronized(pendingRefresh) { pendingRefresh.toList().also { pendingRefresh.clear() } }
        if (paths.isEmpty()) return
        val files = paths.map { File(it) }
        val targets = (files.mapNotNull { it.parentFile }.distinct() + files)
        ApplicationManager.getApplication().invokeLater(
            { LocalFileSystem.getInstance().refreshIoFiles(targets, true, false, null) },
            ModalityState.nonModal(),
        )
    }

    fun refreshProjectTree() {
        val root = project.basePath ?: return
        val dir = LocalFileSystem.getInstance().findFileByPath(root) ?: return
        ApplicationManager.getApplication().invokeLater(
            {
                VfsUtil.markDirtyAndRefresh(true, true, true, dir)
            },
            ModalityState.nonModal(),
        )
    }

    private fun edt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
}
