package dev.lain.claudejb.model.session.launch

enum class IdeServer(val key: String, val label: String) {
    CODE("code", "Code"),
    RUN("run", "Run"),
    VCS("vcs", "VCS"),
    OPS("ops", "Ops"),
    ;

    val mcpName: String get() = key
}
