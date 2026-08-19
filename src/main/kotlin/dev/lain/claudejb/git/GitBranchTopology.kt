package dev.lain.claudejb.git

data class GitBranchTopology(
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val mergeBase: String? = null,
) {

    companion object {

        val NONE = GitBranchTopology()

        fun commitCount(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
