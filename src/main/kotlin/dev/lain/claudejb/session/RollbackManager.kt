package dev.lain.claudejb.session

import com.intellij.openapi.project.Project
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.FileRollback
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
