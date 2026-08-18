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

/**
 * The project tree as the composer's attach menu walks it: **what a folder contains**, and **what marking that
 * folder drags in**. Two questions, one set of rules, so a row the menu refuses to show can never be a path the
 * menu silently attaches.
 *
 * The vocabulary is **root-relative, forward-slashed, in and out**. That is a containment decision, not a display
 * one: a path outside the project cannot even be spelled in this API's terms, so a caller that echoes a value
 * back at us is echoing something already inside the tree. [resolve] is the one door from that vocabulary to a
 * real file, and it is the only place a caller may cross it.
 *
 * **Three rules govern every answer here, and each of them is load-bearing:**
 *
 * 1. **Confined to the project root, canonically.** Every path — the one the page sends and every entry produced
 *    from it — goes through [DiffPresenter.isWithinRoot], the same canonicalize-and-prefix gate that confines what
 *    the binary is allowed to write, with the same fail-closed behaviour on an unresolvable path. It is reused
 *    rather than reimplemented on purpose: a second containment check is how one of them ends up weaker than the
 *    other, and this one already has the symlink-escape and prefix-sibling cases pinned. The input arrives from a
 *    browser, so `..`, an absolute path, a symlink planted at a target outside the tree and a path too long for
 *    the filesystem are ordinary inputs, not exotic ones.
 * 2. **The IDE's index answers, never a raw filesystem walk.** [ProjectFileIndex.isExcluded] is asked about every
 *    entry, and it is true for a file that is excluded **or** ignored — which is precisely how `build/`, `out/`,
 *    `node_modules/` and `.git/` stay out of a listing for free. Walking the disk instead would make "attach this
 *    folder" mean "attach the build output", and it would mean it silently.
 * 3. **Bounded, and honest about the bound.** [MAX_ENTRIES] caps what one answer may contain and [Expansion]
 *    carries the fact that the cap was reached, because a folder that quietly attaches half of itself is worse
 *    than one that refuses: nothing on screen distinguishes the truncated answer from the complete one.
 *
 * **Threading: every entry point must be called off the EDT.** The work is a read action over the VFS and the
 * project index ([ProjectFileIndex.isExcluded] is `@RequiresReadLock`), and a recursive expansion is unbounded in
 * wall time up to its own ceiling; on the EDT that is a frozen IDE. It runs as a cancellable non-blocking read
 * action so a pending write action preempts it instead of blocking behind it, exactly as `LinkResolver` does for
 * the transcript's link resolution — the callers dispatch through `executeOnPooledThread`.
 */
internal object ProjectTree {

    /**
     * Which picker is open. The two browse the same tree and select different things out of it, so the mode is
     * carried through both questions rather than applied by the caller afterwards: a listing that offers a row
     * the expansion would then discard is a menu that lies about what pressing it does.
     */
    enum class Mode { FILES, DIRECTORIES }

    /** One row: what to show, what to send back ([path] is root-relative), and whether it can be opened further. */
    data class Entry(val name: String, val path: String, val directory: Boolean)

    /**
     * What marking a folder drags in.
     *
     * [truncated] is the whole reason this is not a bare `List<String>`. It means "there were more than
     * [MAX_ENTRIES] of them", established by finding the one past the ceiling rather than by counting everything
     * and slicing — so it is exact, and it costs no more than the walk that stopped early.
     */
    data class Expansion(val paths: List<String>, val truncated: Boolean)

    /**
     * The most paths one answer may carry, and the same ceiling for a single listing.
     *
     * Chosen against what is genuinely attachable to a prompt, not against what the machine survives: every path
     * here becomes an `@`-mention the agent is expected to read, so the budget that runs out first is the model's
     * context, long before the walk gets expensive. For scale, this repository tracks 181 Kotlin sources and 465
     * files under `src/` in total — so the ceiling comfortably clears "the whole source tree of a real project",
     * which is the largest thing anybody marks on purpose, and refuses beyond it instead of attaching a slice.
     *
     * A listing that comes back at exactly this size is therefore "the first [MAX_ENTRIES] of more", and the page
     * showing it is expected to say so.
     */
    const val MAX_ENTRIES: Int = 500

    /**
     * Ceiling on entries *visited* during an expansion, as opposed to entries collected.
     *
     * These are different numbers and both are needed: a tree of ten thousand empty directories yields no files
     * at all, so [MAX_ENTRIES] would never stop it. This is what makes the walk terminate on a pathological tree,
     * and it is also what bounds the cost of the per-entry containment check.
     */
    private const val MAX_VISITED = 20_000

    /**
     * Size ceiling for an attachable file, **the same number the composer already enforces on an image**
     * ([ImageAttachments.MAX_IMAGE_BYTES]). Named from one place rather than re-picked here: two thresholds for
     * "too big to put in a turn" drift, and the one that drifts is always the one nobody is looking at.
     */
    private const val MAX_FILE_BYTES = ImageAttachments.MAX_IMAGE_BYTES

    private val NOTHING = Expansion(emptyList(), false)

    /**
     * The direct children of [path] (the empty string being the project root), ordered as an explorer orders them.
     *
     * In [Mode.DIRECTORIES] only directories are returned: the folder picker attaches folders, so offering files
     * it would refuse is noise the user has to learn to ignore. Anything the index calls excluded or ignored, and
     * anything that is not [isAttachableFile], is absent rather than greyed out.
     *
     * **Off the EDT.** Empty on a project with no root, on a path that is not a directory inside it, and on
     * cancellation.
     */
    fun children(project: Project, path: String, mode: Mode): List<Entry> =
        inReadAction(project, emptyList()) { listing(project, path, mode) }

    /**
     * Everything marking the directory at [path] drags in: in [Mode.FILES] every attachable file at or below it,
     * in [Mode.DIRECTORIES] that directory and every directory below it.
     *
     * The marked directory is **included** in [Mode.DIRECTORIES] deliberately — the result is what pressing
     * *Attach* will attach, so the count the page shows is this list's size and the caller adds nothing to it.
     *
     * **This is also how the count is answered, and there is deliberately no second entry point for it.** Counting
     * and resolving are the same bounded walk — nothing can be counted without being enumerated, and the walk stops
     * at the ceiling either way — so a separate counter would be a second implementation of one rule, and the two
     * would eventually disagree about what a folder contains. The preview reads `paths.size` and [Expansion.truncated];
     * confirming reuses the very list that was counted.
     *
     * **Off the EDT.** A symlinked directory encountered on the way down is listed but **not descended into**:
     * following it would let a link inside the tree pull in a subtree from elsewhere, and a link that points at
     * one of its own ancestors would never terminate. The directory the user explicitly marked is exempt — that
     * one is a deliberate choice, not something the walk wandered into.
     */
    fun expand(project: Project, path: String, mode: Mode): Expansion =
        inReadAction(project, NOTHING) { expansion(project, path, mode) }

    /**
     * The absolute file [relativePath] names inside [root], or **null when it does not name one inside it**.
     *
     * The only crossing from this API's root-relative vocabulary to a real file, and therefore the only place the
     * containment rule has to hold. It holds in three steps, and the order matters: the path is resolved against
     * the root, normalized so `.` and `..` are spent lexically, and only then handed to [DiffPresenter.isWithinRoot],
     * which canonicalizes both sides — so a symlink is resolved to its target before the comparison, and a path
     * the filesystem cannot resolve at all is refused rather than compared as a string.
     *
     * An **absolute** input is not a special case: `File(root, "/etc/passwd")` resolves *under* the root on POSIX,
     * so it becomes a harmless miss inside the tree; where the platform instead yields a path outside the root
     * (a foreign drive letter on Windows), the containment check refuses it. Both routes end inside or nowhere.
     */
    fun resolve(root: String?, relativePath: String): File? {
        if (root.isNullOrBlank()) return null
        val candidate = runCatching { File(root, relativePath).toPath().normalize().toFile() }.getOrNull()
        return candidate?.takeIf { DiffPresenter.isWithinRoot(it.path, root) }
    }

    /**
     * Explorer order: directories first, then files, each alphabetically and case-insensitively.
     *
     * Stable by construction — the case-sensitive name breaks a tie the case-insensitive comparison leaves — because
     * a menu whose rows move between two openings of the same folder cannot be navigated from memory, and that is
     * the only way anybody navigates one quickly.
     */
    fun ordered(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }, { it.name }))

    /**
     * Whether a file is worth offering as an attachment: small enough, and text — or an image, which is the one
     * binary shape a turn can actually carry.
     *
     * [binary] is the platform's own verdict about the *name*; a type it does not recognise is deliberately not
     * binary here. Unrecognised means "no rule matched the extension", not "opaque bytes", and hiding every file
     * with an unusual suffix would quietly remove exactly the hand-written config and data files people attach.
     */
    fun isAttachableFile(name: String, sizeBytes: Long, binary: Boolean): Boolean {
        if (sizeBytes > MAX_FILE_BYTES) return false
        if (!binary) return true
        return ImageAttachments.mediaTypeForExtension(name.substringAfterLast('.', "").lowercase()) != null
    }

    /**
     * The bounded breadth-first expansion, with the tree supplied by [childrenOf] rather than read from disk.
     *
     * Pure, and that is what makes the rule testable: the walk cannot invent a child the caller did not hand it,
     * so a test that hands it a directory it declares empty proves the walk never went behind its back to the
     * filesystem — which is the exact failure this feature must not have.
     *
     * Stops the moment it finds the entry past [MAX_ENTRIES], so "more than the ceiling" is answered without
     * finishing the tree, and again at [MAX_VISITED] entries seen so a tree that yields nothing still terminates.
     *
     * **Every rule this function owns is one line of the loop below, and it stays that way.** They are: both
     * ceilings, each a `return` rather than a slice taken afterwards; what is collected, which is the mode's
     * to decide; and what is descended, which is not. Flattening them apart is fine — nesting them was not,
     * and a linter is right about that — but distributing them across named helpers is not: what makes this
     * reviewable is that where it stops, and on which rule, is visible in one place. A helper that also
     * decides when to give up is the version of this to refuse.
     *
     * The other two rules of an expansion are **not here and must not move here**: containment is applied to
     * every entry [childrenOf] produces, and a symlinked directory is listed but not descended. Both belong to
     * whoever supplies the tree (see [expand]), because this function is pure and can only be as confined as
     * what it is handed.
     */
    fun walk(start: Entry, mode: Mode, childrenOf: (Entry) -> List<Entry>): Expansion {
        val wantsDirectories = mode == Mode.DIRECTORIES
        val collected = ArrayList<String>()
        if (wantsDirectories) collected += start.path
        val queue = ArrayDeque<Entry>().apply { add(start) }
        var visited = 0
        while (queue.isNotEmpty()) {
            for (child in childrenOf(queue.removeFirst())) {
                // Ceiling on what was SEEN: a tree of empty directories collects nothing, so the other one
                // would never fire on it and the walk would run until the tree ran out.
                if (++visited > MAX_VISITED) return Expansion(collected, true)
                val wanted = child.directory == wantsDirectories
                // Ceiling on what was COLLECTED, and it stops the walk rather than trimming a finished one:
                // "there is one past the ceiling" is the whole answer, and the rest of the tree is not read.
                if (wanted && collected.size >= MAX_ENTRIES) return Expansion(collected, true)
                if (wanted) collected += child.path
                // Descended whatever the mode: in FILES mode the directories are the road, not the destination.
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
            // The start is read whatever it is; anything reached from it is read only if it is not a link.
            val dir = if (entry == start) startDir else descendable(root, entry.path)
            if (dir == null) emptyList() else visibleChildren(index, root, dir, mode)
        }
    }

    /** The children the index and the containment rule agree on, capped so one enormous directory stays bounded. */
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

    /** The directory at [relative], or null when it is not one, is outside the root, or is a symlink (see [expand]). */
    private fun descendable(root: String, relative: String): VirtualFile? =
        directoryAt(root, relative)?.takeUnless { it.`is`(VFileProperty.SYMLINK) }

    private fun directoryAt(root: String, relative: String): VirtualFile? {
        val dir = resolve(root, relative) ?: return null
        return LocalFileSystem.getInstance().findFileByIoFile(dir)?.takeIf { it.isValid && it.isDirectory }
    }

    /**
     * Whether the platform recognises [name] as a binary format. Asked by NAME, which is the cheap lookup and the
     * one the platform recommends over `VirtualFile.getFileType()` — the latter falls back to sniffing content,
     * which is a read per file and would turn a bounded walk into one.
     */
    private fun isBinary(name: String): Boolean {
        val type = FileTypeRegistry.getInstance().getFileTypeByFileName(name)
        return type != UnknownFileType.INSTANCE && type.isBinary
    }

    /**
     * Runs [body] as a cancellable read action and answers [fallback] when it is cancelled.
     *
     * Cancellation is an ordinary outcome, not an error: the project closed, or a write action needs the lock and
     * this walk is in the way. A menu that shows nothing is the right answer to that; an exception the user has to
     * read is not. The exception is caught HERE, outside the read action, which is why swallowing it is correct —
     * inside one it would have to be rethrown, since eating it is how a plugin becomes uncancellable.
     */
    private fun <T> inReadAction(project: Project, fallback: T, body: () -> T): T = try {
        ReadAction.nonBlocking<T> { body() }.expireWith(project).executeSynchronously()
    } catch (_: ProcessCanceledException) {
        fallback
    }
}
