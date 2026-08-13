package dev.lain.claudejb.session

/**
 * Turns the binary's `get_workspace_diff` reply into the two SIDES a native diff needs.
 *
 * The reply carries hunks, not file contents: for each changed file, the `@@` header's four numbers and the
 * ` `/`-`/`+`-prefixed lines. The IDE's diff viewer wants two whole texts. The NEW side is the working tree,
 * which is on disk and can simply be read; the BASE side has to be reconstructed by applying each hunk
 * BACKWARDS over the current file — replacing the new line range with the old lines the hunk carries.
 *
 * **It refuses rather than guesses.** Before replacing anything, the hunk's own view of the new side (its ` `
 * and `+` lines) is checked against what is actually at that position on disk. A mismatch means the file
 * moved under us — the diff was computed at one instant and read at another, which is ordinary on a tree an
 * agent is still editing — and the honest answer is "no base", not a plausible-looking one. Showing a
 * fabricated left-hand side in a review tool is worse than showing nothing, because the user cannot tell.
 *
 * Pure: takes text in, returns text out. No IDE, no filesystem, no session — so the reconstruction is
 * unit-testable, which is the only reason to trust it.
 */
object WorkspaceDiffReview {

    /** One file, ready for the diff viewer: what it was, what it is, and why the left side may be missing. */
    data class Side(
        val path: String,
        /** The reconstructed base, or null when it could not be rebuilt faithfully — see [Reason]. */
        val base: String?,
        val current: String,
        val reason: Reason,
    )

    /** Why a file has no reconstructed base. [OK] means it has one. */
    enum class Reason {
        OK,

        /** The binary reported stats but withheld the hunks: over its size cap, or read-permission rules. */
        WITHHELD,

        /** Untracked: git emits no hunks for a file it has never seen, so "before" is genuinely empty. */
        UNTRACKED,

        /** Binary file — there is nothing to show line by line. */
        BINARY,

        /** The hunks did not match the file on disk. Deliberately not reconstructed; see the class doc. */
        DIVERGED,
    }

    /**
     * Rebuilds every file's two sides. [readCurrent] returns the working-tree text, or null when the file
     * cannot be read (deleted since, or unreadable) — such a file is skipped entirely rather than shown as an
     * empty pane, which would read as "everything was deleted".
     */
    fun sides(diff: WorkspaceDiff, readCurrent: (String) -> String?): List<Side> =
        diff.files.mapNotNull { file ->
            val current = readCurrent(file.stats.path) ?: return@mapNotNull null
            when {
                file.stats.isBinary -> Side(file.stats.path, null, current, Reason.BINARY)

                // Untracked: no hunks exist, and none are missing — the whole file IS the addition.
                file.stats.isUntracked -> Side(file.stats.path, "", current, Reason.UNTRACKED)

                file.withheld || file.hunks.isEmpty() -> Side(file.stats.path, null, current, Reason.WITHHELD)

                else -> when (val base = baseOf(current, file.hunks)) {
                    null -> Side(file.stats.path, null, current, Reason.DIVERGED)
                    else -> Side(file.stats.path, base, current, Reason.OK)
                }
            }
        }

    /**
     * The file as it was, from the file as it is plus the hunks that changed it. Null when any hunk does not
     * describe what is actually on disk.
     *
     * Hunks are applied from the BOTTOM UP so that replacing one does not shift the line numbers of the ones
     * above it — the same reason a text editor applies multi-range edits in reverse.
     */
    fun baseOf(current: String, hunks: List<WorkspaceDiff.Hunk>): String? {
        val lines = current.split('\n').toMutableList()
        hunks.sortedByDescending { it.newStart }.forEach { hunk ->
            val from = hunk.newStart - 1 // the wire is 1-based; an empty new side reports start 0
            if (from < 0 || from > lines.size || from + hunk.newLines > lines.size) return null
            val onDisk = lines.subList(from, from + hunk.newLines)
            val asHunkSeesIt = hunk.lines.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.drop(1) }
            if (onDisk != asHunkSeesIt) return null
            val old = hunk.lines.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.drop(1) }
            repeat(hunk.newLines) { lines.removeAt(from) }
            lines.addAll(from, old)
        }
        return lines.joinToString("\n")
    }

    /** What the left-hand pane is called, so a missing base says WHY instead of looking empty. */
    fun baseLabel(side: Side, diffBaseLabel: String): String = when (side.reason) {
        Reason.OK -> diffBaseLabel
        Reason.UNTRACKED -> "New file"
        Reason.WITHHELD -> "Not available (too large or restricted)"
        Reason.BINARY -> "Binary file"
        Reason.DIVERGED -> "Changed on disk since the diff was taken"
    }
}
