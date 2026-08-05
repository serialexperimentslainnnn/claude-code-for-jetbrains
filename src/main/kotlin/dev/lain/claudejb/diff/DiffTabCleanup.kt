package dev.lain.claudejb.diff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/**
 * Closes every diff tab the plugin opened just before the IDE writes the project's workspace state, so those
 * tabs never end up persisted.
 *
 * **Why this is needed.** Our diffs are in-memory previews: [DiffPresenter] builds a `ChainDiffVirtualFile`
 * over content reconstructed from a tool input, and a `ChainDiffVirtualFile` is a `LightVirtualFile` whose URL
 * is `mock:///<tab title>`. The platform persists every open editor tab **by URL** and does not filter by file
 * system — `EditorComposite` writes whatever file its composite holds. On the next start it tries to resolve
 * each URL, and a `mock://` URL resolves to nothing, so each surviving tab becomes
 * `WARN EditorsSplitters - No file exists: mock:///…` and a lost tab slot.
 *
 * That was not hypothetical: a workspace here had accumulated **13** such entries — every one of them named
 * `Claude · SKILL.md`, since the title is the file name and a skills repository has one `SKILL.md` per
 * directory — producing 26 warnings on every single start.
 *
 * Restoring them is not an option even in principle: the reconstructed content, the tool call and the session
 * are all gone by then. The tab is worthless after a restart, so the right lifetime is "this IDE session".
 *
 * **Why [projectClosingBeforeSave] and not `projectClosing`.** The platform fires
 * `projectClosingBeforeSave` → saves the project state → fires `projectClosing`. Closing the tabs in
 * `projectClosing` would run *after* the state has already been written, i.e. too late by one step. Periodic
 * autosaves (frame deactivation) can still persist an open diff mid-session, but the save on close overwrites
 * that, so what is on disk when the IDE next starts is clean either way.
 *
 * [ProjectCloseListener] is published on the **application** message bus, hence the `applicationListeners`
 * registration in `plugin.xml` rather than `projectListeners`.
 */
internal class DiffTabCleanup : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) {
        // closeAll() touches the editor, so it needs the EDT; the listener contract does not promise one.
        // invokeAndWait (not invokeLater) because the state is written as soon as this returns — and it runs
        // the block inline when already on the EDT, which is the usual case for a project close.
        ApplicationManager.getApplication().invokeAndWait {
            OpenedDiffsService.getInstance(project).closeAll()
        }
    }
}
