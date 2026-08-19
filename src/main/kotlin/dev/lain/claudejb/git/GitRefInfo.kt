package dev.lain.claudejb.git

data class GitRefInfo(
    val name: String,
    val kind: GitRefKind,
    val hash: String,
    val current: Boolean,
)

enum class GitRefKind {
    LOCAL,
    REMOTE,
    HEAD,
    ;

    val wire: String get() = name.lowercase()
}
