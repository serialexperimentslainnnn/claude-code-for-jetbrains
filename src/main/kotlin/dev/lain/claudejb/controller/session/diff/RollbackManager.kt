package dev.lain.claudejb.controller.session.diff

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.session.diff.FileRollback
import dev.lain.claudejb.model.diff.EditSnapshot
import java.io.File

class RollbackManager(
    private val project: Project,
    private val diffs: DiffLifecycleManager,
    private val reseedReadState: (path: String, mtime: Long) -> Unit,
) {

    fun revertEdit(snapshot: EditSnapshot): Boolean {
        val ok = FileRollback.revert(project, snapshot)
        if (ok) {
            diffs.markForRefresh(snapshot.filePath)
            diffs.refreshTouched()
            reseedReadState(snapshot.filePath, File(snapshot.filePath).lastModified())
        }
        return ok
    }
}
