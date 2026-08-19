package dev.lain.claudejb.session

object WorkspaceDiffReview {

    data class Side(
        val path: String,
        val base: String?,
        val current: String,
        val reason: Reason,
    )

    enum class Reason {
        OK,

        WITHHELD,

        UNTRACKED,

        BINARY,

        DIVERGED,
    }

    fun sides(diff: WorkspaceDiff, readCurrent: (String) -> String?): List<Side> =
        diff.files.mapNotNull { file ->
            val current = readCurrent(file.stats.path) ?: return@mapNotNull null
            when {
                file.stats.isBinary -> Side(file.stats.path, null, current, Reason.BINARY)

                file.stats.isUntracked -> Side(file.stats.path, "", current, Reason.UNTRACKED)

                file.withheld || file.hunks.isEmpty() -> Side(file.stats.path, null, current, Reason.WITHHELD)

                else -> when (val base = baseOf(current, file.hunks)) {
                    null -> Side(file.stats.path, null, current, Reason.DIVERGED)
                    else -> Side(file.stats.path, base, current, Reason.OK)
                }
            }
        }

    fun baseOf(current: String, hunks: List<WorkspaceDiff.Hunk>): String? {
        val lines = current.split('\n').toMutableList()
        hunks.sortedByDescending { it.newStart }.forEach { hunk ->
            val from = hunk.newStart - 1
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

    fun baseLabel(side: Side, diffBaseLabel: String): String = when (side.reason) {
        Reason.OK -> diffBaseLabel
        Reason.UNTRACKED -> "New file"
        Reason.WITHHELD -> "Not available (too large or restricted)"
        Reason.BINARY -> "Binary file"
        Reason.DIVERGED -> "Changed on disk since the diff was taken"
    }
}
