package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import dev.lain.claudejb.diff.DiffPresenter
import java.io.File
import java.net.URLDecoder

/**
 * Where a link in the transcript goes: the browser, an editor, or the Project view.
 *
 * Extracted from `JcefChatPanel` — which is an assembler, and this is a subject of its own: it answers one
 * question ("the user clicked something, where does it lead?") and needs nothing from the session, the host
 * bridge or the transcript. [LinkResolver] decides what may be opened; this opens it.
 */
internal class LinkNavigator(private val project: Project) {

    /**
     * Only **https** links open externally — plain http (a common malware-hosting scheme) is refused, and
     * file:/jar:/javascript: never reach here. Internal `jb://open?file=&line=` links jump to code in the
     * editor, gated to the project root. Links from the untrusted view are strictly gated.
     */
    fun open(url: String) {
        val u = url.trim()
        when {
            u.lowercase().startsWith("https://") -> BrowserUtil.browse(u)

            u.startsWith("jb://open") -> openJbLink(u)

            // A markdown link whose href is a PATH rather than a URL — `[BACKLOG](docs/BACKLOG.md)`. It
            // carries no scheme, so it matched neither branch above and the click did NOTHING: no
            // navigation, no error, nothing in any log. Bare paths written in prose already resolve
            // (LinkResolver confirms them before linking), which made this the odd one out — the more
            // deliberate the link, the less it worked.
            //
            // The scheme test is what keeps this from swallowing the other schemes DOMPurify allows
            // (`mailto:`, `tel:`, `sms:`…): anything with a scheme is not a path, and is ignored as before.
            LinkResolver.isFilePathHref(u) -> openPath(u.substringBefore('#').trim())
        }
    }

    /** Opens the file from a `jb://open?file=<encoded-path>&line=N` link in the editor, gated as below. */
    private fun openJbLink(url: String) {
        val params = url.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(v, Charsets.UTF_8) }.getOrDefault(v)
        }.toMap()
        val raw = params["file"] ?: return
        openPath(raw, params["line"]?.toIntOrNull() ?: 1)
    }

    /**
     * Opens [raw] — project-relative or absolute — in the editor, or reveals it in the tree when it is a
     * directory or an archive. The single authorising gate for every link the transcript can produce.
     *
     * A link normally carries a PROJECT-RELATIVE path; one pointing into the user's home carries an absolute
     * one. Either way this only *builds* the path — [LinkResolver.isOpenable] is what authorises it, and it
     * is the one place that decides, so neither a hand-crafted `jb://` URL nor a markdown href can reach a
     * file we would not have linked ourselves.
     */
    fun openPath(raw: String, line: Int = 1) {
        val path = resolveAgainstRoot(raw) ?: return
        if (!LinkResolver.isOpenable(path, project.basePath)) return // project or the user's own home, nothing else
        // refreshAndFind, not find: a file Claude has just written may not be in the VFS yet, and a plain
        // lookup would return null — the link would silently do nothing until the IDE next refreshed. (The
        // session also refreshes on every successful write; this is the belt to that pair of braces.)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
        // A directory — and an ARCHIVE, which has no meaningful editor either (opening `foo.zip` would just
        // show a binary buffer) — belong in the tree, not in an editor tab.
        if (vf.isDirectory || vf.fileType is ArchiveFileType) {
            revealDirectory(vf)
            return
        }
        OpenFileDescriptor(project, vf, line.coerceAtLeast(1) - 1, 0).navigate(true)
        selectInProjectView(vf)
    }

    /**
     * Turns a link's `file` param into an absolute path: `~/…` is expanded, an absolute path is taken as-is,
     * and a relative one is resolved against the project root. Null when a relative path has no root to
     * resolve against. The caller still gates the result with [LinkResolver.isOpenable].
     */
    private fun resolveAgainstRoot(raw: String): String? {
        val f = File(LinkResolver.expandHome(raw))
        if (f.isAbsolute) return f.path
        val root = project.basePath ?: return null
        return File(root, f.path).path
    }

    /**
     * Mirrors *Autoscroll from Source*: the opened file is also selected in the **Project view**, so you can
     * see where it lives. Deliberately unobtrusive — `requestFocus = false` keeps the caret in the editor you
     * just jumped into, and the tool window is NOT force-opened: if you keep the tree hidden, a link click has
     * no business popping it open. A file outside the project simply isn't in the tree, so we skip it.
     */
    private fun selectInProjectView(file: VirtualFile) {
        if (!DiffPresenter.isWithinRoot(file.path, project.basePath)) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        if (!tw.isVisible) return
        runCatching { ProjectView.getInstance(project).select(null, file, false) }
    }

    /**
     * Reveals something that has no editor to open (a directory, an archive). Inside the project it belongs
     * to the **Project view** — select and expand it there, activating the tool window: unlike the file case,
     * selecting into a hidden tree would make the click look like it did nothing at all. Outside the project
     * (in the user's home) it isn't in the tree, so the only sensible target is the OS file manager. Already
     * gated by [LinkResolver.isOpenable] before we get here.
     */
    private fun revealDirectory(target: VirtualFile) {
        if (DiffPresenter.isWithinRoot(target.path, project.basePath)) {
            val select = { ProjectView.getInstance(project).select(null, target, true) }
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
            if (tw != null) tw.activate(select, true) else select()
        } else {
            RevealFileAction.openDirectory(File(target.path))
        }
    }
}
