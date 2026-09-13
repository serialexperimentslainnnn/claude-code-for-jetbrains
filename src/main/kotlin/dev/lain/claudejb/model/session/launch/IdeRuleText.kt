package dev.lain.claudejb.model.session.launch

internal object IdeRuleText {

    val RULES: Map<IdeRule, String> = mapOf(
        IdeRule.CODE_READ to "To look at a file call read_file, several at once with paths: it reads through the IDE, unsaved " +
            "edits included, and shows the file in the editor's preview tab without taking the focus. On a big file call " +
            "file_outline first and read only the ranges you need. To list a directory call list_directory. Never ls, " +
            "cat, head, tail or sed to look at anything.",
        IdeRule.CODE_SEARCH to "To find text call search_text with queries, one call for all of them, regex when needed; to " +
            "find files by name or glob call find_files; to find a symbol by name call find_symbols; never grep, rg or " +
            "find. The IDE's index answers in one round trip and sees a resolved program, not characters.",
        IdeRule.CODE_NAVIGATE to "To go from a symbol to where it is declared call definition; to its callers and readers, " +
            "references; to what implements or overrides it, implementations; for its type, signature and documentation, " +
            "symbol_info; for its class or call tree, hierarchy. All resolve through the IDE's index; pass positions to " +
            "resolve several symbols in one call.",
        IdeRule.CODE_EDIT to "To change a file call replace_text with edits, one call for every file you touch, old and new " +
            "text exact; to add lines call insert_text; to create a file call create_file; to rewrite whole files call " +
            "write_file with files. Every edit opens as a diff the user sees and reviews, then lands in a real editor tab. Never " +
            "Edit, Write, sed, awk, tee or a heredoc.",
        IdeRule.CODE_EDIT_OPS to "To take back the last change of a file call undo, to put it back call redo: both go through the " +
            "IDE's undo stack for that file's editor, as the Edit menu would. To replace text or a regular expression " +
            "across files call search_replace, with paths to limit it, one undoable command per file and the first " +
            "changed file shown in the editor. For join, duplicate, delete, indent or unindent at a line call line_ops.",
        IdeRule.CODE_REFACTOR to "To rename a symbol call rename: the IDE's refactoring updates every usage. To move a file " +
            "call move_file, to delete one safely call safe_delete: both fix the imports and refuse while usages remain. " +
            "Never rename or move by editing text.",
        IdeRule.CODE_FORMAT to "After editing call reformat on the touched files and optimize_imports on them, both with paths " +
            "in one call: the IDE's formatter with the project's code style, not yours.",
        IdeRule.CODE_DIAGNOSTICS to "Before calling work done call problems on every file you touched and project_problems " +
            "for the whole project; a warning you introduced is yours to fix. Call problems_view to show a tab of the " +
            "Problems window (Qodana, Vulnerable Dependencies and Security Analysis included); call inspections to see " +
            "the profile's inspections and inspect to run them on a file on request; every reveal is without focus.",
        IdeRule.CODE_EDITOR to "Call open_file to put a file in front of the user at a line, in a tab, without taking the " +
            "focus. Call active_file to learn where the user is: file, caret and selection. On an indexing error call " +
            "index_status with wait and retry once it is ready. For the Code menu at a position (override, implement, " +
            "generate, surround, unwrap, comment, move statement or line, rearrange, fold, live templates, quick " +
            "documentation) call editor_action with path, line and column: the editor performs it with the caret there.",
        IdeRule.CODE_ANALYZE to "To run the IDE's inspections on a whole scope call inspect_scope (project, module, dir or " +
            "file): the Inspection Results window shows them. To apply every cleanup fix of the profile call cleanup. " +
            "To learn what the files of a scope depend on call file_dependencies, transitive levels deep when asked; " +
            "direction=backward opens the IDE's Backward Dependencies analysis. To follow where a value comes from or " +
            "goes call dataflow at its position: the IDE's Analyze Data Flow window opens.",
        IdeRule.CODE_ANALYSIS to "Given a stack trace call stack_trace: the frames come back resolved to the project's files " +
            "and the IDE's Analyze Stack Trace dialog opens with it. Call duplicates and infer_nullity to run the IDE's " +
            "duplicate-code and nullity analyses in their own windows. To reach what belongs to a symbol call related " +
            "with test, subject, super or implementations: the tests and subjects come back as data from the IDE's " +
            "test finder and the Navigate action opens the target.",
        IdeRule.CODE_VIEWS to "To show two files side by side call diff_show, or compare for a file against another or " +
            "against the active editor: the IDE's diff opens without taking the focus. To mark a directory as a source, " +
            "test, resource or excluded root call mark_as: the project model changes as the project view's menu would. " +
            "To open a path in the file manager, the IDE's Terminal or its application call open_in.",
        IdeRule.CODE_FILES to "For the file menu call copy_path (absolute, relative, name or the IDE's Copy Reference, onto " +
            "the clipboard), file_type to read or associate a file's type through the IDE's file type manager, ignore to " +
            "add a path to .gitignore or another ignore file (shown in the editor), and delete_file for files with no " +
            "usages to check, all paths in one call; a symbol or a used file goes through safe_delete.",
        IdeRule.CODE_REFACTOR_OPS to "For the rest of the Refactor menu call introduce (variable, constant, field, parameter " +
            "or functional_parameter), extract (method, interface, superclass, delegate or module), inline, or members " +
            "(pull_up, push_down, change_signature, move, encapsulate_fields and the other member refactorings) at a " +
            "position or on a selection given with to_line: the IDE's own refactoring runs with its dialog or in-place " +
            "editor for the user to finish, in a tab that never takes the focus.",
        IdeRule.CODE_TEMPLATES to "Call templates to see the IDE's live templates and template_apply to expand one at a position, " +
            "as its key and Tab would, in the file's tab without focus; call file_templates to see the file templates and " +
            "file_from_template to create a file from one in a directory, as New would, shown in the editor.",
        IdeRule.CODE_LANGUAGE to "Call injections to see the language fragments the IDE injects into a file's literals, inject_at " +
            "to inject a language into a literal through IntelliLang's temporary injections, and docs to show the IDE's " +
            "quick documentation popup for a symbol without taking the focus.",
        IdeRule.CODE_BOOKMARKS to "Call bookmarks, bookmark_add and bookmark_remove for the IDE's bookmarks on files and lines, " +
            "shown in the Bookmarks window; call project_view to select a file in the Project window's pane without " +
            "taking the focus.",
        IdeRule.CODE_PSI to "When the text is not enough call psi_tree for the IDE's syntax tree of a file and psi_at for the " +
            "element under a position with its parents; to change code structurally call psi_replace or psi_insert with " +
            "text parsed in the file's language: the PSI keeps references and formatting consistent, one undoable " +
            "command, the file shown in the editor.",
        IdeRule.CODE_INDEX to "Call index_keys and index_query for a file-based index by name, and stub_query for a stub " +
            "index's keys or elements: the IDE's indexes answer without reading files.",
        IdeRule.CODE_UAST to "For Java, Kotlin, Scala or Groovy call uast_tree and uast_at: the IDE's unified AST, the same " +
            "shape across those languages, with lines and source text.",
        IdeRule.CODE_WORKSPACE to "Call workspace for the IDE's workspace model entities (modules, content roots, source roots, " +
            "libraries, SDKs) with the source that created them, read-only.",
        IdeRule.CODE_MARKUP to "To point the user at code without editing it call mark_add: a highlighted, warning or error " +
            "range, or a gutter icon with a tooltip, in every editor of the file, which the IDE shows in the preview tab; call hint_add " +
            "for an inline hint at a position, marks to list what you left and mark_remove to take one back.",
        IdeRule.CODE_PRESENCE to "To ask the user something about a file where they read it call banner_show: a banner over " +
            "its editor with action labels, and banner_clear reports which they chose. Call status to put a short text in " +
            "the IDE's status bar and scratch_create for a scratch file that opens in the editor and is never committed.",
        IdeRule.CODE_RECENT to "To know where the user has been call recent: the files they opened last (kind=files) or " +
            "changed last (kind=changed_files), from the IDE's editor history. To move their editor through that " +
            "history when asked call navigate_history with back, forward, last_change or next_change. To show the " +
            "clipboard against a file call compare_clipboard: the IDE's diff window opens without focus. To list or " +
            "switch the theme, the color scheme, the keymap or the code style call scheme.",
        IdeRule.RUN_BUILD to "To build call build: the IDE's own build with the compiler's errors and their positions, shown " +
            "in the Build window, for the project, a module or one file. To run tests call run_tests with a path, a " +
            "name or a class and read the tree it returns; tests lists what the project has. Never a command line, a " +
            "script or Gradle by hand to build or test.",
        IdeRule.RUN_RUN to "Call run_configurations to see what the project already runs and run_configuration to run one, " +
            "exactly as the Run button does, with executor for debug, coverage or the profiler, output streaming to the " +
            "chat and to the Run window; a utility that has no " +
            "configuration gets one under .idea/runConfigurations named Tool: <name>. Call processes to list or stop " +
            "what is running.",
        IdeRule.RUN_TERMINAL to "A command goes through shell, never Bash: it runs over the socket in the Terminal window " +
            "the user sees, in a tab named Claude, with its exit code and the end of its output, while a new process " +
            "would cost a guard pass and a permission. Every command line goes here, whatever it does: gh and glab, " +
            "git, curl, jq, base64, grep or find over build reports, a pipeline, a script. There is no command for " +
            "which Bash is the right instrument while this server answers, and a Bash call made while shell exists " +
            "is the defect the last rule names. Several commands go in one call, chained with ; or &&. The tab is " +
            "shown without focus and never switched while the user is in the Terminal; terminal_tabs lists the window's " +
            "tabs and closes one of Claude's by name.",
        IdeRule.RUN_OPS to "Call edit_configuration to open the IDE's run configuration editor at a configuration for the " +
            "user, attach to open its Attach to Process chooser, and coverage to show the Coverage window or switch, " +
            "hide, report or import a suite after a run with executor=coverage.",
        IdeRule.RUN_DEBUG to "To debug call session to start a run configuration under the debugger, breakpoint to set or " +
            "clear breakpoints, step to step over, into, out, force or smart into, or mute them, frames for the stack and " +
            "values for the variables of a " +
            "frame: the IDE shows the execution point as you go. Never prints. Call session with stop when done.",
        IdeRule.VCS_READ to "Before deciding anything about the tree call git_status, git_diff, git_log and git_branches: " +
            "git as the IDE sees it, read-only. git_log with hashes returns each commit and selects it in the Log.",
        IdeRule.VCS_WRITE to "To stage call git_stage with paths, to commit call git_commit with paths and a message, both " +
            "in one call for the whole list; to create, check out or delete a branch call git_branch; to fetch, pull or " +
            "push call git_remote. Each goes through the IDE's Git, signs as the IDE would and draws one card; the " +
            "commit message is read by the guard like any other text.",
        IdeRule.VCS_FORGE to "Call vcs_open to show the Log (at a hash, or only a range with range=A..B), a file's history, " +
            "the Commit window or the Pull Requests view, without taking the focus; call vcs_action to open any entry of " +
            "the IDE's Git menu and its GitHub and GitLab submenus by name (pull, push, merge, rebase and their abort or " +
            "continue, branches, stash, shelve, tag, reset, worktrees, annotate, clone, pull and merge requests, gists, " +
            "accounts) for the user to finish, with path or hash when the entry acts on a file or a commit. For the pull " +
            "requests of the GitHub repository call pull_requests (open, closed, merged or all) and pull_request with a " +
            "number for its branches, review decision and description: both go through the IDE's GitHub account, and " +
            "the Pull Requests view is shown. Never gh or glab.",
        IdeRule.VCS_LOG_OPS to "For what the Log's commit menu offers on a commit (cherry-pick, checkout, browse at revision, " +
            "compare with local, reset, revert, undo, reword, fixup, squash, drop, interactive rebase, push up to, new " +
            "branch or tag, copy revision, open in browser) call commit_action with the hash: the commit is selected in " +
            "the Log and the action runs as the menu would. For what the Branches popup offers (merge, rebase, compare, " +
            "diff with local, rename, delete, checkout, checkout as new, new tag) call branch_op: the IDE's own branch " +
            "machinery with its progress and conflict handling. Call worktrees and remotes to list, add or remove " +
            "working trees and remotes through the IDE's Git.",
        IdeRule.VCS_CHANGES to "For the uncommitted work call stash (save, pop, apply, drop, list through the IDE's Git), " +
            "shelve (the IDE's shelf: list, shelve by name, unshelve), patch (create writes the changes as a unified diff, " +
            "apply opens the IDE's Apply Patch dialog) and rollback (the IDE's Rollback on the given files, undoable from " +
            "Local History); paths go all in one call.",
        IdeRule.VCS_HISTORY to "To know who wrote a line call blame: the IDE's annotations, and the gutter is shown. For the " +
            "commits that touched a file call file_history, and the history tab is shown. For the IDE's Local History " +
            "call local_history: show its view, put a label before a risky change, revert to a label. For a file's content " +
            "at a branch, tag or commit call file_at: the IDE's diff against the working tree is shown.",
        IdeRule.VCS_PR_OPS to "A pull request is driven as data through the IDE's GitHub account, never through gh: call " +
            "pr_create with base, head, title and body once the head branch is pushed (the Pull Requests view is shown), " +
            "pr_comment to post on its conversation, pr_checks to read its mergeability and every check on its head " +
            "commit, polling until they settle or wait runs out (call again while settled is false), and pr_merge to " +
            "merge it with a merge commit once can_merge is true; it refuses and names what blocks it otherwise. A merge " +
            "into a branch that publishes on merge publishes: say so before calling it.",
        IdeRule.VCS_RELEASE to "To verify what a release left behind call tags for the repository's tags with their commits, " +
            "workflow_runs for the GitHub Actions runs of a branch with status and conclusion (call again while a run is " +
            "queued or in_progress), release for the GitHub Release of a tag with its assets, and marketplace for the " +
            "plugin's versions on the JetBrains Marketplace; the first three go through the IDE's GitHub account, the " +
            "last through the public Marketplace API. Never gh.",
        IdeRule.OPS_SERVICES to "The Services window is the DevOps panel: call services to see its tree as the user does, " +
            "service_actions to see what the IDE offers on a node, service_action to perform one exactly as clicking it " +
            "would, service_open to reveal the node. Never kubectl, docker or podman.",
        IdeRule.OPS_PROJECT to "Call project for the SDK and the structure, modules for the modules with their roots, " +
            "dependencies for what a module depends on, all from the IDE's project model; dependency_add adds a library " +
            "through Gradle, Maven or npm as the IDE would; call plugins before relying on a tool window, an action or a " +
            "file type a plugin provides.",
        IdeRule.OPS_IDE to "Call tool_window to open or close a tool window without taking the focus, settings_open to open " +
            "Settings at a page, ide_action to perform any registered action by id when no other tool covers it, and " +
            "notify to raise a notification the user sees.",
        IdeRule.OPS_ACTIONS to "Every entry of the IDE's menus is an action: call actions with a fragment of its text or id to " +
            "find it, plugins included, and menu with a path such as Code/Analyze or Git/GitHub to walk the main menu " +
            "as the user sees it; then fire it with ide_action and a target. Call appearance to flip presentation, " +
            "distraction-free, full-screen, zen, compact or the Presentation Assistant, and ui to show or hide the " +
            "toolbar, navigation bar, tool window bars, status bar or main menu; both say the state they left.",
        IdeRule.OPS_SERVICE_VIEW to "For a Services node's console text (a container's log, a run's output) call service_data; " +
            "to expand or extract a node in the Services window call service_expand or service_extract; to learn what " +
            "changed among the services since you last looked call service_events with the last sequence number.",
        IdeRule.OPS_REMOTE to "For the closed Tools entries call deployment (upload, download, sync, compare, browse, configure), " +
            "ssh_session, qodana (results as data with the Qodana tab shown, run and open through the plugin's actions) and " +
            "vulnerable_dependencies (the Package Checker's findings, and the IDE shows its Problems tab): each goes " +
            "through the actions that plugin registers on this IDE and is refused when the plugin is missing.",
        IdeRule.OPS_TOOLS_MENU to "For the Tools menu's generators call javadoc (the IDE's Generate JavaDoc dialog), launcher " +
            "(the command-line launcher or desktop entry), xml (validate a file into the Problems view, generate a DTD or " +
            "a schema) and markdown (import a docx, export, table of contents, pandoc): each opens the IDE's own dialog " +
            "for the user.",
        IdeRule.OPS_CONSOLES to "Call groovy_console, kotlin_bytecode (the bytecode panel beside a file), kotlin_configure and " +
            "python_console to open the IDE's language consoles and tools; each is refused when its plugin is missing.",
        IdeRule.OPS_WINDOW to "Call tabs to see the editor's tab groups or to close, pin, split or move a tab as the Window " +
            "menu would; layout to store, restore or hide the tool window layout; zoom to zoom the editor's font or the " +
            "whole IDE; editor_settings to show or hide line numbers, whitespace, soft wraps or gutter icons in every " +
            "editor, saying the state it left.",
        IdeRule.OPS_DATA to "Call db_connections, db_schema and db_query for the Database window's data sources; http_files " +
            "to find the HTTP Client's request files, http_run to run one with its response console and http_open to show " +
            "one in the editor; ssh_hosts for the configured SSH hosts. Never psql, curl or ssh from the command line.",
        IdeRule.COMMON_SHOW to "Asked to see or open something: open it in the IDE with the tool that reveals it and say " +
            "what you opened; the user's focus stays where it was.",
        IdeRule.COMMON_QUERY to "Questions about the project or the IDE are answered from its tools, never from memory; " +
            "say what you looked at.",
        IdeRule.COMMON_PRS to "Pull requests: list them with pull_requests, ask which one, read it with pull_request, review " +
            "its branch with git_log and file_at, report; merge requests live in the IDE's GitLab view through vcs_action.",
        IdeRule.COMMON_BATCH to "One call carries the whole list: read_file(paths), write_file(files), replace_text(edits), " +
            "search_text(queries), git_commit(paths). Never one item per call; independent calls go out in one message.",
        IdeRule.COMMON_AGENTS to "Every agent you spawn receives this block verbatim and works the same way, in batches.",
        IdeRule.COMMON_TOOLS to "Scripts serve utilities, never builds or tests; they go under ./.claudetools, /.claudetools/ in " +
            ".gitignore first.",
        IdeRule.COMMON_FALLBACK to "Name a failing server in one line before any fallback, never silently.",
        IdeRule.COMMON_REPORT to "A native tool used while ours existed is the defect: name it and stop.",
    )
}
