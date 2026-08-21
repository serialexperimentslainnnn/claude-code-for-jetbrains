package dev.lain.claudejb.forge

data class ForgePullRequest(
    val number: Long,
    val title: String,
    val url: String,
    val state: String,
    val draft: Boolean,
    val author: String?,
    val sourceBranch: String?,
)

enum class ForgeRunStatus(val wire: String) {

    RUNNING("running"),

    COMPLETED("completed"),

    FAILED("failed"),

    STOPPED("stopped"),
}

data class ForgeRun(
    val id: Long,
    val name: String?,
    val status: ForgeRunStatus,
    val url: String,
    val finishedAtIso: String?,
)
