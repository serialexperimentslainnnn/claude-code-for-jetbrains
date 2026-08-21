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

enum class ForgeAccessLevel(val wire: String) {

    NONE("none"),

    READ("read"),

    WRITE("write"),

    ADMIN("admin"),
    ;

    val atLeastRead: Boolean get() = ordinal >= READ.ordinal

    val atLeastWrite: Boolean get() = ordinal >= WRITE.ordinal
}

data class ForgeAccess(val level: ForgeAccessLevel, val login: String?) {

    val canComment: Boolean get() = level.atLeastRead

    val canApprove: Boolean get() = level.atLeastRead

    val canRunPipelines: Boolean get() = level.atLeastWrite

    val canMerge: Boolean get() = level.atLeastWrite

    val canOpen: Boolean get() = level.atLeastWrite

    fun authored(by: String?): Boolean = login != null && by != null && login.equals(by, ignoreCase = true)
}

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
