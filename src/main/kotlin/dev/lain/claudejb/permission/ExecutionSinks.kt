package dev.lain.claudejb.permission

object ExecutionSinks {

    private val HOOK_DIRS = listOf("/.git/hooks/", "/.githooks/")

    val HOOK_NAMES: List<String> = listOf(
        "applypatch-msg", "pre-applypatch", "post-applypatch",
        "pre-commit", "pre-merge-commit", "prepare-commit-msg", "commit-msg", "post-commit",
        "pre-rebase", "post-checkout", "post-merge", "pre-push", "post-rewrite",
        "pre-auto-gc", "post-index-change", "push-to-checkout", "post-update", "reference-transaction",
    )

    private val RC_NAMES = setOf(
        ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile",
        ".zshrc", ".zshenv", ".zprofile", ".zlogin", ".zlogout",
        ".kshrc", ".mkshrc", "bash.bashrc", "zshrc", "zshenv", "zprofile", "config.fish",
    )

    private val SINK_PATH = Regex(
        """/\.git/hooks/|/\.githooks/|/\.config/autostart/|/\.config/systemd/|/etc/systemd/system/""" +
            """|/etc/systemd/user/|/library/launchagents/|/library/launchdaemons/""" +
            """|/etc/cron\.[a-z]+/|/etc/cron\.d/|/etc/crontab$|/var/spool/cron/|/\.config/fish/""",
        RegexOption.IGNORE_CASE,
    )

    fun isSink(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        if (SINK_PATH.containsMatchIn(p)) return true
        return p.substringAfterLast('/') in RC_NAMES
    }

    fun hookFiles(projectRoot: String): List<String> {
        val root = projectRoot.trimEnd('/')
        return HOOK_DIRS.flatMap { dir -> HOOK_NAMES.map { root + dir + it } }
    }
}
