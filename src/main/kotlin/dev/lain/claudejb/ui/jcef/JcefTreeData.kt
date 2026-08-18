package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.context.ProjectTree
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two answers the composer's in-menu project browser waits for — one folder's contents, and what marking
 * that folder would drag in.
 *
 * <pre>
 * cc.treeChildren({ path, mode, truncated, entries: [{ name, path, directory }] })
 * cc.treeExpansion({ path, mode, truncated, paths: [String] })
 * </pre>
 *
 * **Pure**, like every other `Jcef*` builder: it takes what [ProjectTree] already collected and returns JSON.
 * The collection is the half that takes a read lock and walks the VFS, so it must happen off the EDT and
 * therefore cannot happen here; keeping it out is what lets the shape be pinned on a plain JVM.
 *
 * **`path` and `mode` are echoed back rather than remembered by the page**, and that is not redundancy. Both
 * answers arrive asynchronously into a menu the user is still driving: they may unfold two folders before
 * either replies, and they may press Back and come in through the other picker while a reply is in flight. An
 * answer that did not say which question it was answering would be applied to whichever folder happened to be
 * open, and the symptom — a folder showing another folder's contents — looks like a listing bug rather than a
 * routing one.
 *
 * **`truncated` is derived HERE, from the ceiling itself.** A listing that comes back at exactly
 * [ProjectTree.MAX_ENTRIES] is "the first N **of more**"; an expansion carries its own flag because the walk
 * that produced it knows. Either way the page is told rather than left to notice a round number, because
 * nothing on screen distinguishes a complete answer from a cut one — and a folder that quietly attaches half
 * of itself is worse than one that refuses.
 *
 * **`internal`, and forced to be**: both entry points take [ProjectTree]'s own types, and that object is
 * `internal` because it is the door to the project index with the containment gate inside it. Kotlin will not
 * let a public signature carry an internal type, and the two ways out are not equivalent — widening
 * [ProjectTree] to satisfy the compiler would enlarge a security surface for a reason that has nothing to do
 * with security. Every other builder here that touches an internal type is `internal` for the same reason.
 */
internal object JcefTreeData {

    /**
     * One folder's contents.
     *
     * [entries] arrive in [ProjectTree]'s own explorer order (directories first, then files, each
     * case-insensitively by name) and are emitted in it: a menu whose rows move between two openings of the
     * same folder cannot be navigated from memory, which is the only way anybody navigates one quickly. An
     * empty array is a real answer — a folder with nothing attachable in it — and the page says so.
     */
    fun childrenJson(path: String, mode: String, entries: List<ProjectTree.Entry>): JsonObject =
        buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("truncated", entries.size >= ProjectTree.MAX_ENTRIES)
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        addJsonObject {
                            put("name", entry.name)
                            put("path", entry.path)
                            put("directory", entry.directory)
                        }
                    }
                },
            )
        }

    /**
     * What marking that folder drags in — the paths themselves, never a count.
     *
     * There is deliberately no number in this payload beyond what the array's own length says: counting and
     * resolving are the same bounded walk in [ProjectTree], so a separate total would be a second opinion
     * about the same question, and the two would eventually disagree about what a folder contains. The page
     * reads `paths.length` for the button and reuses that very list when the button is pressed.
     */
    fun expansionJson(path: String, mode: String, expansion: ProjectTree.Expansion): JsonObject =
        buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("truncated", expansion.truncated)
            put("paths", buildJsonArray { expansion.paths.forEach { add(it) } })
        }
}
