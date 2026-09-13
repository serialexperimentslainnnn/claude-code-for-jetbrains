package dev.lain.claudejb.model.session.launch

enum class IdeRule(val key: String, val server: IdeServer?, val label: String, val tools: Set<String>) {
    CODE_READ("code.read", IdeServer.CODE, "Read through the index", setOf("read_file", "file_outline", "list_directory")),
    CODE_SEARCH("code.search", IdeServer.CODE, "Search through the index", setOf("search_text", "find_files", "find_symbols")),
    CODE_NAVIGATE(
        "code.navigate",
        IdeServer.CODE,
        "Navigate by symbols, not by text",
        setOf("definition", "references", "implementations", "symbol_info", "hierarchy"),
    ),
    CODE_EDIT("code.edit", IdeServer.CODE, "Edit through the IDE", setOf("replace_text", "insert_text", "create_file", "write_file")),
    CODE_EDIT_OPS("code.edit_ops", IdeServer.CODE, "The Edit menu on a file", setOf("undo", "redo", "search_replace", "line_ops")),
    CODE_REFACTOR("code.refactor", IdeServer.CODE, "Refactor with the IDE's engine", setOf("rename", "move_file", "safe_delete")),
    CODE_FORMAT("code.format", IdeServer.CODE, "Format with the project's code style", setOf("reformat", "optimize_imports")),
    CODE_DIAGNOSTICS(
        "code.diagnostics",
        IdeServer.CODE,
        "No new problems before calling work done",
        setOf("problems", "project_problems", "problems_view", "inspect", "inspections"),
    ),
    CODE_EDITOR(
        "code.editor",
        IdeServer.CODE,
        "Show the user what you touch",
        setOf("open_file", "active_file", "index_status", "editor_action"),
    ),
    CODE_ANALYZE(
        "code.analyze",
        IdeServer.CODE,
        "Inspect, clean up, dependencies, data flow",
        setOf("inspect_scope", "cleanup", "file_dependencies", "dataflow"),
    ),
    CODE_ANALYSIS(
        "code.analysis",
        IdeServer.CODE,
        "Stack traces, duplicates, nullity, related",
        setOf("stack_trace", "duplicates", "infer_nullity", "related"),
    ),
    CODE_VIEWS("code.views", IdeServer.CODE, "Diffs, roots, open in", setOf("diff_show", "compare", "mark_as", "open_in")),
    CODE_FILES("code.files", IdeServer.CODE, "The file menu", setOf("copy_path", "file_type", "ignore", "delete_file")),
    CODE_REFACTOR_OPS(
        "code.refactor_ops",
        IdeServer.CODE,
        "The rest of the Refactor menu",
        setOf("introduce", "extract", "inline", "members"),
    ),
    CODE_TEMPLATES(
        "code.templates",
        IdeServer.CODE,
        "Live and file templates",
        setOf("templates", "template_apply", "file_templates", "file_from_template"),
    ),
    CODE_LANGUAGE("code.language", IdeServer.CODE, "Injected languages and docs", setOf("injections", "inject_at", "docs")),
    CODE_BOOKMARKS(
        "code.bookmarks",
        IdeServer.CODE,
        "Bookmarks and the project view",
        setOf("bookmarks", "bookmark_add", "bookmark_remove", "project_view"),
    ),
    CODE_PSI("code.psi", IdeServer.CODE, "The syntax tree", setOf("psi_tree", "psi_at", "psi_replace", "psi_insert")),
    CODE_INDEX("code.index", IdeServer.CODE, "The indexes by name", setOf("index_keys", "index_query", "stub_query")),
    CODE_UAST("code.uast", IdeServer.CODE, "The unified AST", setOf("uast_tree", "uast_at")),
    CODE_WORKSPACE("code.workspace", IdeServer.CODE, "The workspace model", setOf("workspace")),
    CODE_MARKUP("code.markup", IdeServer.CODE, "Marks in the editor", setOf("mark_add", "mark_remove", "marks", "hint_add")),
    CODE_PRESENCE(
        "code.presence",
        IdeServer.CODE,
        "Banners, status, scratches",
        setOf("banner_show", "banner_clear", "status", "scratch_create"),
    ),
    CODE_RECENT(
        "code.recent",
        IdeServer.CODE,
        "Where the user has been, and the IDE's schemes",
        setOf("recent", "navigate_history", "compare_clipboard", "scheme"),
    ),
    RUN_BUILD("run.build", IdeServer.RUN, "Build and test through the IDE", setOf("build", "run_tests", "tests")),
    RUN_RUN(
        "run.run",
        IdeServer.RUN,
        "Run configurations, not commands; create one when the task has none",
        setOf("run_configurations", "run_configuration", "processes"),
    ),
    RUN_TERMINAL("run.terminal", IdeServer.RUN, "Commands run in the IDE's terminal", setOf("shell", "terminal_tabs")),
    RUN_OPS("run.ops", IdeServer.RUN, "The Run menu beyond starting", setOf("edit_configuration", "attach", "coverage")),
    RUN_DEBUG(
        "run.debug",
        IdeServer.RUN,
        "Debug with breakpoints instead of prints",
        setOf("session", "step", "frames", "values", "breakpoint"),
    ),
    VCS_READ("vcs.read", IdeServer.VCS, "Git through the IDE", setOf("git_status", "git_diff", "git_log", "git_branches")),
    VCS_WRITE("vcs.write", IdeServer.VCS, "Commit through the IDE", setOf("git_stage", "git_commit", "git_branch", "git_remote")),
    VCS_FORGE(
        "vcs.forge",
        IdeServer.VCS,
        "The forge through the IDE's views",
        setOf("vcs_open", "vcs_action", "pull_requests", "pull_request"),
    ),
    VCS_LOG_OPS(
        "vcs.log_ops",
        IdeServer.VCS,
        "The Log's commit menu and the Branches popup",
        setOf("commit_action", "branch_op", "worktrees", "remotes"),
    ),
    VCS_CHANGES("vcs.changes", IdeServer.VCS, "Stash, shelve, patch, rollback", setOf("stash", "shelve", "patch", "rollback")),
    VCS_HISTORY("vcs.history", IdeServer.VCS, "A file's past", setOf("blame", "file_history", "local_history", "file_at")),
    VCS_PR_OPS("vcs.pr_ops", IdeServer.VCS, "Pull requests as data", setOf("pr_create", "pr_comment", "pr_checks", "pr_merge")),
    VCS_RELEASE("vcs.release", IdeServer.VCS, "What a release left behind", setOf("tags", "workflow_runs", "release", "marketplace")),
    OPS_SERVICES(
        "ops.services",
        IdeServer.OPS,
        "Services is the DevOps panel",
        setOf("services", "service_actions", "service_action", "service_open"),
    ),
    OPS_PROJECT(
        "ops.project",
        IdeServer.OPS,
        "The project model",
        setOf("project", "modules", "dependencies", "dependency_add", "plugins"),
    ),
    OPS_IDE("ops.ide", IdeServer.OPS, "Move the IDE for the user", setOf("tool_window", "settings_open", "ide_action", "notify")),
    OPS_ACTIONS("ops.actions", IdeServer.OPS, "Every menu entry is one action away", setOf("actions", "menu", "appearance", "ui")),
    OPS_SERVICE_VIEW(
        "ops.service_view",
        IdeServer.OPS,
        "Services in depth",
        setOf("service_data", "service_extract", "service_expand", "service_events"),
    ),
    OPS_REMOTE(
        "ops.remote",
        IdeServer.OPS,
        "The closed Tools entries",
        setOf("deployment", "ssh_session", "qodana", "vulnerable_dependencies"),
    ),
    OPS_TOOLS_MENU("ops.tools_menu", IdeServer.OPS, "Javadoc, launcher, XML, Markdown", setOf("javadoc", "launcher", "xml", "markdown")),
    OPS_CONSOLES(
        "ops.consoles",
        IdeServer.OPS,
        "Language consoles",
        setOf("groovy_console", "kotlin_bytecode", "kotlin_configure", "python_console"),
    ),
    OPS_WINDOW("ops.window", IdeServer.OPS, "Tabs, layout, zoom and editor settings", setOf("tabs", "layout", "zoom", "editor_settings")),
    OPS_DATA(
        "ops.data",
        IdeServer.OPS,
        "Data through the IDE",
        setOf("db_connections", "db_schema", "db_query", "http_files", "http_run", "http_open", "ssh_hosts"),
    ),
    COMMON_SHOW("common.show", null, "Asked to see something, open it in the IDE", emptySet()),
    COMMON_QUERY("common.query", null, "Questions about the project are answered from the IDE", emptySet()),
    COMMON_PRS("common.prs", null, "Pull requests: list, ask, open in the IDE, review", emptySet()),
    COMMON_BATCH("common.batch", null, "Independent calls go out together; lists go whole", emptySet()),
    COMMON_AGENTS("common.agents", null, "Agents and subagents inherit these rules", emptySet()),
    COMMON_TOOLS("common.tools", null, "Own tools live under .claudetools, ignored by git", emptySet()),
    COMMON_FALLBACK("common.fallback", null, "A failing server is said out loud, never worked around silently", emptySet()),
    COMMON_REPORT("common.report", null, "A native tool used while its IDE replacement existed is a defect", emptySet()),
    ;

    companion object {
        fun of(key: String): IdeRule? = entries.firstOrNull { it.key == key }

        fun forServer(server: IdeServer): List<IdeRule> = entries.filter { it.server == server }

        val common: List<IdeRule> get() = entries.filter { it.server == null }

        fun active(rules: Set<IdeRule>, servers: Set<IdeServer>): Set<IdeRule> =
            if (servers.isEmpty()) emptySet() else rules.filterTo(LinkedHashSet()) { it.server == null || it.server in servers }

        fun parse(csv: String): Set<IdeRule> =
            csv.split(',').map { it.trim() }.mapNotNull(::of).toSet()

        fun csv(rules: Collection<IdeRule>): String = rules.sortedBy { it.ordinal }.joinToString(",") { it.key }
    }
}
