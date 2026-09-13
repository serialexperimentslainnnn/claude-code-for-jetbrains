# Skill inventory — what Claude can do in the IDE

Every capability Claude has inside the IDE is an MCP tool served by the plugin itself. This is the
inventory: what exists today, how each tool is reached, and what the roadmap still owes. The order of
work lives in [`MCP_ROADMAP.md`](MCP_ROADMAP.md); the architecture in [`../DIRECTIVES.md`](../DIRECTIVES.md).

## How a tool is reached

Four MCP servers run inside the plugin, one per family, over Unix sockets: **`code`**, **`run`**,
**`vcs`** and **`ops`**. Each one exposes only three meta-tools, so nothing loads up front:

| Meta-tool | What it does |
|---|---|
| `domains()` | Lists the server's domains, one line each. Always first. |
| `tools(domain)` | Lists the tools of one domain with their parameters. Only for the domain about to be used. |
| `run(tool, args)` | Runs one tool. Every result is TOON. The Security Guard judges `args` before anything runs. |

A request in the chat reaches a tool through that ladder: *"read `Foo.kt`"* becomes
`mcp__code__domains` → `mcp__code__tools(read)` → `mcp__code__run(read_file, {path})`. The examples below
write only the last step, as `server ▸ tool {args}`.

Rules that hold for every tool:

- **Lists**: any tool with a `paths`, `queries`, `names`, `positions`, `edits`, `files`, `hashes` or
  `statements` parameter runs once per item in a single call and draws one card per item. Up to 50 items.
  `git_stage` and `git_commit` take `paths` as one call, not a batch.
- **Long tools** (`build`, `run_configuration`, `run_tests`, `shell`, `http_run`) stream their output to the
  card, answer `status: running` after `wait` seconds, and are resumed with `job`.
- **Positions** are 1-based `line` and `column`; paths are absolute or relative to the project root.
- Every enumerating tool carries a `max`, and says `truncated` when it hit it.
- A tool marked *mutates* is a change the user sees in the IDE (a diff, a refresh, a dialog).

## `code` — the project as the IDE resolves it

### read · search

| Tool | Capability | When · example |
|---|---|---|
| `read_file` | A file as the editor holds it, unsaved edits included; `offset`/`limit` for big files; several with `paths`. | Any "look at", "open", "what does X contain". `code ▸ read_file {paths: ["src/A.kt", "src/B.kt"], limit: 120}` |
| `search_text` | Text or regex across the project; one row per hit with file and line, no text. | "where is X used", "find the string". `code ▸ search_text {queries: ["TODO", "class .*Test"], regex: true}` |
| `find_files` | Files by exact name or glob. | "where is the file called". `code ▸ find_files {names: ["*.http", "Guard*.kt"]}` |
| `list_directory` | A directory as the project tree shows it, excluded entries left out, `depth` levels. | "what is in this folder". `code ▸ list_directory {path: "src/main/kotlin", depth: 2}` |

### navigate · outline · hierarchy

| Tool | Capability | When · example |
|---|---|---|
| `find_symbols` | Classes, functions and other named symbols whose name contains the query, as Go to Symbol does; `libraries` to include them. | "which classes are called …Tools". `code ▸ find_symbols {queries: ["Tools", "Guard"]}` |
| `definition` | The declaration the reference at a position resolves to. | "where is this defined". `code ▸ definition {path: "A.kt", line: 9, column: 24}` |
| `references` | Every place that references the symbol at a position. | "who calls this", "is this used". `code ▸ references {path: "A.kt", line: 14, column: 9}` |
| `implementations` | Implementations or overrides of the symbol at a position. | "who implements this interface". `code ▸ implementations {path: "I.kt", line: 6}` |
| `file_outline` | The declarations of a file as a tree with their lines, like the Structure view. | Before reading a big file. `code ▸ file_outline {paths: ["Session.kt"], depth: 2}` |
| `symbol_info` | Kind, name, declaring signature and location of the symbol at a position. | "what is this thing". `code ▸ symbol_info {positions: [{path: "A.kt", line: 21, column: 47}]}` |
| `hierarchy` | Callers or callees of the symbol at a position, nested up to `depth` 3. | "trace who reaches this". `code ▸ hierarchy {path: "A.kt", line: 123, kind: "callers", depth: 3}` |

### diagnostics · inspect

| Tool | Capability | When · example |
|---|---|---|
| `problems` | Errors and warnings the IDE's analysis shows for a file (opens it), with line, column, severity and inspection; `severity` error/warning/weak/all. | Before calling any edit done. `code ▸ problems {paths: ["A.kt", "B.kt"], severity: "warning"}` |
| `project_problems` | Everything the Problems view lists across the project; `group` filters by inspection family or plugin. | "is the project clean", "what does Qodana say". `code ▸ project_problems {group: "Qodana"}` |
| `problems_view` | Lists the Problems tool window's tabs, or shows one to the user. | "show me the security findings". `code ▸ problems_view {tab: "Security Analysis"}` |
| `inspections` | The inspections of the current profile — id, name, group, enabled — filtered by a query. | To find an inspection id. `code ▸ inspections {query: "unused"}` |
| `inspect` | Runs the profile's enabled inspections on a file, or one by id; findings with line, severity and message; hints below `severity` stay out. | "run the inspections on this file", "is there anything unused here". `code ▸ inspect {path: "A.kt", inspection: "UnusedSymbol"}` |

### edit

| Tool | Capability | When · example |
|---|---|---|
| `replace_text` *mutates* | One literal replacement (all with `replace_all`), one undo entry, saved, shown as a diff; several files with `edits`. | Any targeted change. `code ▸ replace_text {edits: [{path: "A.kt", old_string: "x", new_string: "y"}]}` |
| `insert_text` *mutates* | Whole lines before a line (one past the end appends). | Adding a member or an import. `code ▸ insert_text {path: "A.kt", line: 5, content: "fun twice() = 2"}` |
| `create_file` *mutates* | A new file, directories created, opened; fails if it exists. | "create a test for". `code ▸ create_file {files: [{path: "src/test/X.kt", content: "…"}]}` |
| `write_file` *mutates* | A whole rewrite as one undo entry and one diff, or creation when absent. | A file that changes more than it keeps. `code ▸ write_file {path: "A.kt", content: "…"}` |

### edit_ops

| Tool | Capability | When · example |
|---|---|---|
| `undo` *mutates* | Edit ▸ Undo on a file through the IDE's undo stack; returns whether there was anything to undo. | "take that back". `code ▸ undo {path: "A.kt"}` |
| `redo` *mutates* | Edit ▸ Redo on a file. | "put it back". `code ▸ redo {path: "A.kt"}` |
| `search_replace` *mutates* | Replace in Files with Replace All: text or regex across the files that match (or only `paths`, one call), one undoable command per file, first file shown. | A rename of a string across the project. `code ▸ search_replace {query: "foo", replacement: "bar", paths: ["A.kt", "B.kt"]}` |
| `line_ops` *mutates* | join, duplicate, delete, indent or unindent at a line, as the editor would. | "duplicate line 12". `code ▸ line_ops {action: "duplicate", path: "A.kt", line: 12}` |

### refactor · format

| Tool | Capability | When · example |
|---|---|---|
| `rename` *mutates* | The IDE's Rename on the symbol at a position, or the file; every reference follows; fails on conflict. | "rename X to Y". `code ▸ rename {path: "A.kt", line: 6, column: 9, new_name: "salute"}` |
| `move_file` *mutates* | The IDE's Move: packages, imports and references follow. | "move this into package p". `code ▸ move_file {path: "A.kt", destination: "src/main/kotlin/p"}` |
| `safe_delete` *mutates* | Deletes a symbol or a file only when nothing uses it; otherwise lists the blocking usages. | "remove this if unused". `code ▸ safe_delete {path: "A.kt", line: 7, column: 9}` |
| `reformat` *mutates* | Reformat Code on a file or a line range, with the project's code style. | After editing. `code ▸ reformat {paths: ["A.kt", "B.kt"]}` |
| `optimize_imports` *mutates* | Optimize Imports on a file. | After editing. `code ▸ optimize_imports {path: "A.kt"}` |

### editor

| Tool | Capability | When · example |
|---|---|---|
| `open_file` | Opens a file at a line and column, as Go to File does. | "show me", and on every file edited. `code ▸ open_file {path: "A.kt", line: 14}` |
| `active_file` | The selected editor with caret and selection, plus every open file. | "what am I looking at". `code ▸ active_file {}` |
| `index_status` | Whether the IDE is indexing; `wait` blocks until it is done. | On an indexing error, before symbol tools. `code ▸ index_status {wait: true}` |
| `editor_action` *mutates* | The Code menu at a position: override, implement, delegate, generate, surround, unwrap, comment_line/block, move_statement/element/line, rearrange, auto_indent, insert/save_template, fold/unfold (+recursively, +all), update_copyright, quick_doc/definition/type. Caret placed, file in a tab without focus. | "override toString here". `code ▸ editor_action {action: "override", path: "A.kt", line: 12}` |

### analyze

| Tool | Capability | When · example |
|---|---|---|
| `inspect_scope` *mutates* | Code ▸ Inspect Code on project, module, dir or file; the Inspection Results window shows them. | "inspect the whole module". `code ▸ inspect_scope {scope: "module", module: "app"}` |
| `cleanup` *mutates* | Code ▸ Code Cleanup on a scope, one undoable command. | "clean up this package". `code ▸ cleanup {scope: "dir", path: "src/main/kotlin/x"}` |
| `file_dependencies` | What the files of a scope depend on (forward, `transitive` levels); backward opens the IDE's analysis. | "what does this file pull in". `code ▸ file_dependencies {scope: "file", path: "A.kt"}` |
| `dataflow` | Analyze Data Flow to/from the expression at a position, in the IDE's window. | "where does this value come from". `code ▸ dataflow {path: "A.kt", line: 12, column: 9, direction: "to"}` |

### analysis

| Tool | Capability | When · example |
|---|---|---|
| `stack_trace` | Frames of a trace resolved to project files; the Analyze Stack Trace dialog opens with the text. | A pasted exception. `code ▸ stack_trace {text: "…"}` |
| `duplicates` | Locate Duplicates on a file or the project, in the IDE's window. | "is this duplicated anywhere". `code ▸ duplicates {path: "A.kt"}` |
| `infer_nullity` *mutates* | Infer Nullity (Java) with the IDE's dialog. | "annotate nullability". `code ▸ infer_nullity {path: "A.java"}` |
| `related` | Tests of a class, the subject of a test (data), super method, implementations; the Navigate action opens it. | "where are the tests for this". `code ▸ related {kind: "test", path: "A.kt", line: 5}` |

### views

| Tool | Capability | When · example |
|---|---|---|
| `diff_show` | The IDE's diff of two files. | "diff these two". `code ▸ diff_show {left: "A.kt", right: "B.kt"}` |
| `compare` | A file against another or against the active editor. | "compare with what I have open". `code ▸ compare {path: "A.kt"}` |
| `mark_as` *mutates* | Mark Directory as source, test, resources, test_resources, excluded, or unmark. | "this is a test root". `code ▸ mark_as {path: "src/it", kind: "test"}` |
| `open_in` | Reveal in the file manager, open in the IDE's Terminal, or in the associated app. | "open the folder". `code ▸ open_in {path: "build", where: "file_manager"}` |

### files

| Tool | Capability | When · example |
|---|---|---|
| `copy_path` | absolute, relative, name onto the clipboard and returned; reference runs Copy Reference. | "copy the path". `code ▸ copy_path {path: "A.kt", kind: "absolute"}` |
| `file_type` *mutates* | The file type the IDE assigns; with `type`, associates the name with it. | "treat this as JSON". `code ▸ file_type {path: "x.cfg", type: "JSON"}` |
| `ignore` *mutates* | Adds a path to .gitignore or another ignore file; the file opens. | "ignore the build dir". `code ▸ ignore {path: "build"}` |
| `delete_file` *mutates* | Deletes paths through the VFS, one call, inside the project only. | Scratch files with no usages. `code ▸ delete_file {paths: ["tmp.txt"]}` |

### refactor_ops

| Tool | Capability | When · example |
|---|---|---|
| `introduce` *mutates* | Introduce variable, constant, field, parameter or functional_parameter at a position or selection. | "extract this into a constant". `code ▸ introduce {kind: "constant", path: "A.kt", line: 8, column: 12, to_line: 8, to_column: 30}` |
| `extract` *mutates* | Extract method, interface, superclass, delegate or module. | "extract these lines into a method". `code ▸ extract {kind: "method", path: "A.kt", line: 10, to_line: 14}` |
| `inline` *mutates* | Refactor ▸ Inline at a position. | "inline this variable". `code ▸ inline {path: "A.kt", line: 9, column: 5}` |
| `members` *mutates* | pull_up, push_down, change_signature, move, encapsulate_fields, make_static, convert_to_instance, inheritance_to_delegation, anonymous_to_inner, method_object. | "change the signature". `code ▸ members {action: "change_signature", path: "A.kt", line: 20, column: 9}` |

### templates

| Tool | Capability | When · example |
|---|---|---|
| `templates` | The live templates: key, group, description, text; `query`. | Before `template_apply`. `code ▸ templates {query: "main"}` |
| `template_apply` *mutates* | Expands a live template at a position, as key + Tab would; the user fills the variables. | "put a for loop here". `code ▸ template_apply {key: "fori", path: "A.kt", line: 12}` |
| `file_templates` | The file templates: name, extension, text. | Before `file_from_template`. `code ▸ file_templates {query: "Kotlin"}` |
| `file_from_template` *mutates* | New ▸ template in a directory with `props`; the file opens. | "create a Kotlin class Foo in x". `code ▸ file_from_template {template: "Kotlin Class", dir: "src/main/kotlin/x", name: "Foo"}` |

### language

| Tool | Capability | When · example |
|---|---|---|
| `injections` | The language fragments injected into a file's literals. | "is that SQL recognised". `code ▸ injections {path: "Dao.kt"}` |
| `inject_at` *mutates* | Inject a language into the literal at a position (IntelliLang). | "treat this string as JSON". `code ▸ inject_at {path: "A.kt", line: 9, column: 20, language: "JSON"}` |
| `docs` | The quick documentation popup for a symbol. | "what does this do". `code ▸ docs {path: "A.kt", line: 9, column: 5}` |

### bookmarks

| Tool | Capability | When · example |
|---|---|---|
| `bookmarks` | Every bookmark: group, file, line, mnemonic, description. | "where did I leave marks". `code ▸ bookmarks {}` |
| `bookmark_add` *mutates* | A bookmark on a file or a line, in a group, with a description. | "remember this spot". `code ▸ bookmark_add {path: "A.kt", line: 40, description: "fix here"}` |
| `bookmark_remove` *mutates* | Remove the bookmarks of a file, or the one on a line. | `code ▸ bookmark_remove {path: "A.kt", line: 40}` |
| `project_view` | Select a file in the Project window, switching pane if asked. | "show it in the tree". `code ▸ project_view {path: "A.kt"}` |

### psi

| Tool | Capability | When · example |
|---|---|---|
| `psi_tree` | The syntax tree of a file or of the element at a line, to a depth. | When text is not enough. `code ▸ psi_tree {path: "A.kt", line: 12, depth: 2}` |
| `psi_at` | The leaf at a position and its parents. | "what is this token". `code ▸ psi_at {path: "A.kt", line: 12, column: 9}` |
| `psi_replace` *mutates* | Replace the element (or a parent) with text parsed in the file's language, reformatted. | Structural edits. `code ▸ psi_replace {path: "A.kt", line: 12, column: 9, parent: 1, text: "foo(1)"}` |
| `psi_insert` *mutates* | Insert parsed text before or after the element. | `code ▸ psi_insert {path: "A.kt", line: 12, text: "val x = 1", where: "after"}` |

### index

| Tool | Capability | When · example |
|---|---|---|
| `index_keys` | The keys of a file-based index by name. | `code ▸ index_keys {index: "TodoIndex"}` |
| `index_query` | The files behind one key. | `code ▸ index_query {index: "filetypes", key: "Kotlin"}` |
| `stub_query` | A stub index's keys, or the elements behind a key. | "every class named Foo". `code ▸ stub_query {index: "java.class.shortname", key: "Foo"}` |

### uast (IDEs with the Java plugin)

| Tool | Capability | When · example |
|---|---|---|
| `uast_tree` | The unified AST of a JVM-language file to a depth. | Cross-language analysis. `code ▸ uast_tree {path: "A.kt", depth: 2}` |
| `uast_at` | The UAST node at a position and its parents. | `code ▸ uast_at {path: "A.kt", line: 12, column: 9}` |

### workspace

| Tool | Capability | When · example |
|---|---|---|
| `workspace` | The workspace model's modules, content roots, source roots, libraries or SDKs, with their entity source. | "what did Gradle import". `code ▸ workspace {entity_type: "source_root"}` |

### markup

| Tool | Capability | When · example |
|---|---|---|
| `mark_add` *mutates* | A highlight, warning or error range over lines, or a gutter icon with a tooltip; returns an id. | "show me where the bug is". `code ▸ mark_add {path: "A.kt", line: 12, to_line: 14, kind: "warning", tooltip: "null here"}` |
| `mark_remove` *mutates* | Remove a mark or hint by id. | `code ▸ mark_remove {id: 3}` |
| `marks` | The marks of the session, all or for a file. | `code ▸ marks {path: "A.kt"}` |
| `hint_add` *mutates* | An inline hint before or after a position, as parameter hints look. | "annotate what this returns". `code ▸ hint_add {path: "A.kt", line: 12, column: 20, text: ": Int"}` |

### presence

| Tool | Capability | When · example |
|---|---|---|
| `banner_show` *mutates* | A banner over a file's editor with action labels; the click is reported by `banner_clear`. | A choice tied to a file. `code ▸ banner_show {path: "A.kt", text: "Migrate this?", actions: ["Yes", "Later"]}` |
| `banner_clear` *mutates* | Removes the banner; returns the chosen action. | `code ▸ banner_clear {path: "A.kt"}` |
| `status` *mutates* | Text in the status bar. | "tell me when it's done". `code ▸ status {text: "Claude: tests green"}` |
| `scratch_create` *mutates* | A scratch file with a language and content, opened. | Notes, queries, drafts. `code ▸ scratch_create {name: "plan.md", content: "# Plan"}` |

### recent

| Tool | Capability | When · example |
|---|---|---|
| `recent` | Recently opened files (`kind=files`) or recently changed ones (`changed_files`), newest first. | "what was I working on". `code ▸ recent {kind: "changed_files"}` |
| `navigate_history` *mutates* | Navigate ▸ Back, Forward, Last Edit Location, Next Edit Location on the user's editor. | "go back to where I was". `code ▸ navigate_history {direction: "back"}` |
| `compare_clipboard` | View ▸ Compare with Clipboard against a file, in the IDE's diff window. | "diff this against what I copied". `code ▸ compare_clipboard {path: "A.kt"}` |
| `scheme` *mutates* | List or set the theme, color scheme, keymap or code style, as Quick Switch Scheme does. | "switch to the dark theme". `code ▸ scheme {kind: "theme", action: "set", name: "Dark"}` |

## `run` — build, run, test, shell, debug

| Tool | Capability | When · example |
|---|---|---|
| `build` *mutates* | The IDE's build, incremental or `rebuild`, a `module` or one `file`, with the compiler's errors and positions; streamed. | "does it compile". `run ▸ build {kind: "module", module: "app", wait: 110}` |
| `run_configurations` | The run configurations as the Run combo shows them: name, type, temporary, selected. | Before running anything. `run ▸ run_configurations {}` |
| `run_configuration` *mutates* | Starts one as the Run button does, before-launch tasks included; `executor` run, debug, coverage or profile; exit code and console tail; several with `names`. | "run the gates", any project script that already has a configuration. `run ▸ run_configuration {name: "Tool: lint", wait: 110}` |
| `processes` *mutates* | The Run tool window's tabs, or stops one by name. | "is it still running", "stop it". `run ▸ processes {action: "stop", name: "Kotlin tests"}` |
| `run_tests` *mutates* | Tests through the IDE's runner: a file, several, the test at a line, or a named configuration; pass/fail/ignored and each failure's message and frame. | "run this test". `run ▸ run_tests {name: "ToolModelTest", wait: 110}` |
| `tests` | The test classes and methods the IDE's frameworks recognise in a file, with lines. | "what tests are in here". `run ▸ tests {path: "src/test/X.kt"}` |
| `shell` *mutates* | A command in the user's shell inside a Terminal tab; exit code and tail. Replaces Bash. | Any command; several chained in one call. `run ▸ shell {command: "git log -3 --oneline", wait: 20}` |
| `terminal_tabs` *mutates* | The Terminal window's tabs (name, selected, ours, running) or close one by name. | "close your tab". `run ▸ terminal_tabs {action: "close", name: "Claude"}` |
| `edit_configuration` *mutates* | Run ▸ Edit Configurations at a configuration. | `run ▸ edit_configuration {name: "Kotlin tests"}` |
| `attach` *mutates* | Run ▸ Attach to Process chooser. | `run ▸ attach {}` |
| `coverage` *mutates* | The Coverage window; switch, hide, report, import. | After `executor: "coverage"`. `run ▸ coverage {action: "report"}` |
| `session` *mutates* | Start a configuration under the debugger and wait for the first stop; status with frames and variables; stop; list. | "debug this test". `run ▸ session {action: "start", name: "ToolModelTest"}` |
| `step` *mutates* | over, into, out, force_into, smart_into, resume, pause, mute, run_to a line, or wait; answers with the session status. | Once suspended. `run ▸ step {kind: "run_to", path: "A.kt", line: 22}` |
| `frames` | Threads and the stack of one; `frame` selects the current frame for `values`. | "where is it stopped". `run ▸ frames {max: 5}` |
| `values` *mutates* | Variables of the current frame; `eval` an expression; `set` a variable. | "what is x here". `run ▸ values {action: "eval", code: "tools.size"}` |
| `breakpoint` *mutates* | Add (with `condition`, `temporary`), remove or list line breakpoints. | Before `session`. `run ▸ breakpoint {action: "add", path: "A.kt", line: 21}` |

## `vcs` — Git and the forge through the IDE

| Tool | Capability | When · example |
|---|---|---|
| `git_status` | The working tree as the Changes view sees it: branch, HEAD, upstream, ahead/behind, every changed path with its type. | Before staging or committing, and before any claim about the tree. `vcs ▸ git_status {}` |
| `git_log` | Recent commits with hash, subject, author, date and files; one or several `hashes` with their paths; `all_branches`. | "what changed lately", "what did commit X touch". `vcs ▸ git_log {hashes: ["5db1226"]}` |
| `git_diff` | The unified diff of the uncommitted changes: whole tree, one path, or several. | Reviewing before a commit. `vcs ▸ git_diff {paths: ["A.kt"], max_lines: 200}` |
| `git_branches` | Every local and remote branch with its commit, current first. | "which branches exist". `vcs ▸ git_branches {}` |
| `git_stage` *mutates* | Stages (`add`) or unstages (`reset`) paths through the IDE's Git. | Only the paths touched, never blind. `vcs ▸ git_stage {action: "add", paths: ["A.kt"]}` |
| `git_commit` *mutates* | Commits what is staged, or only `paths`; signing and hooks as the user's Git configures them. | One commit per logical unit. `vcs ▸ git_commit {message: "fix(x): …", paths: ["A.kt"]}` |
| `git_branch` *mutates* | Creates a branch or checks one out (`start_point` creates it there). Deleting is the user's. | "start a branch for". `vcs ▸ git_branch {action: "checkout", name: "feature/x", start_point: "develop"}` |
| `git_remote` *mutates* | Fetch, pull or push with the IDE's credentials; returns upstream and ahead/behind. Push is the maintainer's call. | "fetch". `vcs ▸ git_remote {action: "fetch"}` |
| `vcs_open` | Shows a VCS view: the Git log (at a `hash`, or only a `range` such as `v5.8.1..HEAD`), a file's history, the Commit window, or the pull-requests view. | "open the log", "compare the branch with the last release". `vcs ▸ vcs_open {view: "log", range: "v5.8.1..HEAD"}` |
| `pull_requests` | The GitHub repository's pull requests through the IDE's account: number, title, state, draft, author, updated, url; `state` open/closed/merged/all. The Pull Requests view is shown. | "what PRs are open". `vcs ▸ pull_requests {state: "open"}` |
| `pull_request` | One pull request by number with base, head, review decision and body; `open` shows the view and opens it in the browser. | "show me #42". `vcs ▸ pull_request {number: 42, open: true}` |
| `vcs_action` *mutates* | Every entry of the Git menu and its GitHub/GitLab submenus by name: pull, push, fetch, merge, rebase (+abort/continue/skip), cherry-pick continue/abort, revert_abort, branches, new_branch, rename_branch, compare_with_branch, stash, unstash, stash_silently, show_stash, shelve, show_shelf, rollback, annotate, compare_same_version, file_history, tag, reset, resolve_conflicts, commit, update, unshallow, worktrees, new_worktree, configure_remotes, clone, init, create_pull_request, pull_requests, share_on_github, clone_github, sync_fork, create_gist, github_accounts, create_merge_request, merge_requests, clone_gitlab, create_snippet, gitlab_accounts; `path` or `hash` for entries that act on a file or a commit. | When the user must confirm in the IDE. `vcs ▸ vcs_action {action: "annotate", path: "A.kt"}` |

### log_ops

| Tool | Capability | When · example |
|---|---|---|
| `commit_action` *mutates* | The Log's commit menu on a hash: cherry_pick, checkout, browse_at_revision, compare_with_local, reset_to, revert, undo, reword, fixup, squash_into, squash, drop, interactive_rebase, push_up_to, add_to_remote_branch, new_branch, new_tag, copy_revision, open_in_browser. The commit is selected in the Log first. | "cherry-pick that commit". `vcs ▸ commit_action {action: "cherry_pick", hash: "d20afbe"}` |
| `branch_op` *mutates* | The Branches popup through `GitBrancher`: merge, rebase, rebase_onto, compare, diff_with_local, rename, delete, checkout, checkout_as_new, new_tag; `target` is the other name. | "merge develop into this branch". `vcs ▸ branch_op {action: "merge", ref: "develop"}` |
| `worktrees` *mutates* | list, add (path, optional new branch) or remove a working tree. | "add a worktree for the hotfix". `vcs ▸ worktrees {action: "add", path: "../hotfix", branch: "hotfix/x"}` |
| `remotes` *mutates* | list, add, remove or rename a remote (`url` carries the new name for rename). | "add the upstream remote". `vcs ▸ remotes {action: "add", name: "upstream", url: "git@github.com:org/repo.git"}` |

### changes

| Tool | Capability | When · example |
|---|---|---|
| `stash` *mutates* | list, save (with message), pop, apply, drop through the IDE's Git; the stash list after. | "stash this while I check main". `vcs ▸ stash {action: "save", message: "wip"}` |
| `shelve` *mutates* | The IDE's shelf: list, shelve (name, optional `paths`) with rollback, unshelve by name. | "shelve these two files". `vcs ▸ shelve {action: "shelve", name: "spike", paths: ["A.kt", "B.kt"]}` |
| `patch` *mutates* | create writes the changes (or `paths`) as a unified diff to `path`; apply opens the IDE's Apply Patch dialog. | "make me a patch". `vcs ▸ patch {action: "create", path: "wip.patch"}` |
| `rollback` *mutates* | The IDE's Rollback on the given changed files, one call, undoable from Local History. | "throw away my changes to A.kt". `vcs ▸ rollback {paths: ["A.kt"]}` |

### history

| Tool | Capability | When · example |
|---|---|---|
| `blame` | Line, commit, author, date for lines `from`..`to` from the IDE's annotations; the gutter is shown. | "who wrote this". `vcs ▸ blame {path: "A.kt", from: 10, to: 20}` |
| `file_history` | The commits that touched a file, renames followed; the history tab is shown. | "when did this change". `vcs ▸ file_history {path: "A.kt"}` |
| `local_history` *mutates* | show the IDE's Local History of a file; label the project before a risky change; revert a file to a label of this session. | Before a big refactor. `vcs ▸ local_history {path: "A.kt", action: "label", label: "before-rename"}` |
| `file_at` | A file's content at a ref, and the IDE's diff of it against the working tree. | "how was this on main". `vcs ▸ file_at {path: "A.kt", ref: "main"}` |

### pull_request_ops

| Tool | Capability | When · example |
|---|---|---|
| `pr_create` *mutates* | Opens a pull request through the IDE's GitHub account: base, head, title, body, draft; the Pull Requests view is shown. | "open the PR to develop". `vcs ▸ pr_create {base: "develop", head: "feature/x", title: "…", body: "…"}` |
| `pr_comment` *mutates* | A comment on a pull request's conversation, as the IDE's account. | "leave a note on #74". `vcs ▸ pr_comment {number: 74, body: "…"}` |
| `pr_checks` | Mergeability and every check on the head commit, polled every 10 s until nothing is pending or `wait` runs out; `settled` and `can_merge` say where it stands. | "is the CI green". `vcs ▸ pr_checks {number: 74, wait: 110}` |
| `pr_merge` *mutates* | A merge commit through the IDE's account, only when the checks have settled green and the merge state is clean; refuses otherwise, naming the blocker. Merging into a branch that publishes on merge publishes. | "merge it". `vcs ▸ pr_merge {number: 74}` |

### release

| Tool | Capability | When · example |
|---|---|---|
| `tags` | The repository's tags on GitHub with their commits. | "is v6.0.0 tagged". `vcs ▸ tags {max: 5}` |
| `workflow_runs` | GitHub Actions runs, optionally of one branch: status, conclusion, url. | "did the release job pass". `vcs ▸ workflow_runs {branch: "main", max: 5}` |
| `release` | The GitHub Release of a tag with its assets. | "is the Release out, with the zip and the signatures". `vcs ▸ release {tag: "v6.0.0"}` |
| `marketplace` | The plugin's versions on the JetBrains Marketplace, from the public API, no account. | "is 6.0.0 on the Marketplace". `vcs ▸ marketplace {}` |

## `ops` — the Services panel, the project, the IDE, data

| Tool | Capability | When · example |
|---|---|---|
| `services` | The Services tree as the user sees it: path, name, contributing plugin, state; `filter`. Only nodes with services, as the view shows. | Before any other services tool. `ops ▸ services {filter: "Docker"}` |
| `service_actions` | The actions the IDE offers on a node, with id, text and enabled. | To know what `service_action` can do. `ops ▸ service_actions {path: "Docker/Docker/Containers/web"}` |
| `service_action` *mutates* | Performs one of those actions exactly as clicking it would. | "stop the container", "connect to Docker". `ops ▸ service_action {path: "Docker/Docker/Containers/web", action: "Stop Container"}` |
| `service_open` | Reveals a node in the Services window. | "show me the cluster". `ops ▸ service_open {path: "Docker/Docker"}` |
| `project` | Name, base path, SDK, indexing, module count, active VCSs. | First call in an unknown project. `ops ▸ project {}` |
| `modules` | The modules as Project Structure shows them. | "how is the project split". `ops ▸ modules {}` |
| `dependencies` | One module's order entries in classpath order, with scope. | "what does the main module depend on". `ops ▸ dependencies {module: "app.main"}` |
| `dependency_add` *mutates* | Adds an existing library to a module through the project model (not for Gradle/Maven, which edit the build file). | Plain IntelliJ projects only. `ops ▸ dependency_add {module: "app", library: "junit", scope: "test"}` |
| `ide_action` *mutates* | Any registered IDE action by id, as its menu entry would; with a target it runs as that context menu would: a file (`path`, `line`, `column` — file, PSI and an editor on it), a commit (`hash` — selected in the Git Log), a Services node (`node`). | What no other tool covers. `ops ▸ ide_action {action_id: "Git.CompareWithBranch"}` · `ops ▸ ide_action {action_id: "Vcs.CherryPick", hash: "d20afbe"}` · `ops ▸ ide_action {action_id: "OverrideMethods", path: "src/A.kt", line: 12}` |
| `actions` | Every action the IDE registers, plugins included: id, menu text, description, group, enabled in the project context; `query` on id or text. | To find the id for `ide_action`. `ops ▸ actions {query: "cherry"}` |
| `menu` | The main menu as the user sees it: top level, or the items of one menu by path. | "what is under Code ▸ Analyze". `ops ▸ menu {path: "Code/Analyze"}` |
| `appearance` *mutates* | View ▸ Appearance modes: presentation, distraction_free, full_screen, zen, compact, assistant; toggle or set with `on`; returns the state. | "put the IDE in presentation mode". `ops ▸ appearance {mode: "presentation", on: true}` |
| `ui` *mutates* | Show or hide the toolbar, navigation_bar, tool_window_bars, status_bar or main_menu; toggle or set with `on`. | "hide the status bar". `ops ▸ ui {part: "status_bar", on: false}` |
| `service_data` | The console/editor text inside a Services node's panel (last `tail` lines). | "show me the container log". `ops ▸ service_data {path: "Docker/Docker/Containers/web", tail: 100}` |
| `service_extract` *mutates* | Extract a node into its own tab. | `ops ▸ service_extract {path: "Docker/Docker/Containers/web"}` |
| `service_expand` *mutates* | Expand a node in the tree. | `ops ▸ service_expand {path: "Docker/Docker"}` |
| `service_events` | Service events since a sequence number: added, removed, changed, reset. | "did anything change". `ops ▸ service_events {since: 12}` |
| `deployment` *mutates* | Tools ▸ Deployment: upload, download, sync, compare, browse, configure through the plugin's actions. | `ops ▸ deployment {action: "upload", path: "src"}` |
| `ssh_session` *mutates* | Tools ▸ Start SSH Session. | `ops ▸ ssh_session {}` |
| `qodana` *mutates* | Qodana results as data with the tab shown; run/open through the plugin. | `ops ▸ qodana {action: "results"}` |
| `vulnerable_dependencies` | The Package Checker's findings, tab shown. | `ops ▸ vulnerable_dependencies {}` |
| `javadoc` *mutates* | Tools ▸ Generate JavaDoc dialog, scoped to a path. | `ops ▸ javadoc {path: "src/main/java"}` |
| `launcher` *mutates* | Create Command-line Launcher or Desktop Entry. | `ops ▸ launcher {action: "script"}` |
| `xml` *mutates* | Validate an XML file, generate a DTD or an XSD schema. | `ops ▸ xml {action: "validate", path: "pom.xml"}` |
| `markdown` *mutates* | Import a docx, export, table of contents, pandoc settings. | `ops ▸ markdown {action: "export", path: "README.md"}` |
| `groovy_console` *mutates* | Tools ▸ Groovy Console. | `ops ▸ groovy_console {}` |
| `kotlin_bytecode` *mutates* | Show Kotlin Bytecode for a file. | `ops ▸ kotlin_bytecode {path: "A.kt"}` |
| `kotlin_configure` *mutates* | Configure Kotlin in Project. | `ops ▸ kotlin_configure {}` |
| `python_console` *mutates* | The Python console. | `ops ▸ python_console {}` |
| `tabs` *mutates* | The editor's tab groups with tabs, selection and pins; or close, close_others, close_all, pin, split_right, split_down, unsplit, move_to_opposite on a tab. | "split the editor with A.kt on the right". `ops ▸ tabs {action: "split_right", path: "A.kt"}` |
| `layout` *mutates* | save_default, restore_default or hide_all for the tool window layout. | "hide everything". `ops ▸ layout {action: "hide_all"}` |
| `zoom` *mutates* | in, out or reset on the selected editor's font (`scope=editor`) or the whole IDE (`ide`). | "make it bigger". `ops ▸ zoom {action: "in", scope: "ide"}` |
| `editor_settings` *mutates* | line_numbers, whitespace, soft_wraps or gutter_icons in every editor; toggle or set with `on`. | "show whitespace". `ops ▸ editor_settings {setting: "whitespace", on: true}` |
| `tool_window` *mutates* | Open, close or list tool windows. | "show the Problems view". `ops ▸ tool_window {action: "open", id: "Problems View"}` |
| `settings_open` *mutates* | Settings at a page by display name. | "open the plugin settings". `ops ▸ settings_open {name: "Claude Code"}` |
| `plugins` | The IDE's plugins with id, version and enabled; `filter`. | Before relying on a plugin; to know the IDE build (`com.intellij`). `ops ▸ plugins {filter: "database"}` |
| `notify` *mutates* | A balloon in the IDE's notification area. | A finished long task or a decision needed while the chat is hidden. `ops ▸ notify {title: "Build", message: "green", kind: "info"}` |
| `db_connections` | The Database tool window's data sources: name, DBMS, redacted URL. | First db call. `ops ▸ db_connections {}` |
| `db_schema` | Tables and views of a source, or the columns of one table. | "what tables are there". `ops ▸ db_schema {connection: "local", table: "users"}` |
| `db_query` *mutates* | One or several SQL statements over the IDE's connection and credentials; rows or update count. Reads by intent; a write is the user's to approve. | "how many rows". `ops ▸ db_query {connection: "local", code: "select count(*) from users"}` |
| `http_files` | The project's `.http`/`.rest` request files. | Before `http_run`. `ops ▸ http_files {}` |
| `http_run` *mutates* | Runs every request of a file through the HTTP Client's run configuration; console tail; streamed. | "call the API from the .http file". `ops ▸ http_run {path: "api/users.http", wait: 45}` |
| `http_open` | Opens a request file in the editor. | "show me the requests". `ops ▸ http_open {path: "api/users.http"}` |
| `ssh_hosts` | The SSH hosts the IDE knows: host, port, user, authentication kind; never the secret. | "which hosts are configured". `ops ▸ ssh_hosts {}` |

## Not tools, but capabilities that ride on them

- **The Security Guard** judges every `run(tool, args)` inside the server; a refusal comes back as the
  tool's error with the rule and the string that tripped it. The answer is to change that string, not the tool.
- **Cards**: every own call is a card in the chat, one per item in a list, with live lines for long tools, a
  diff and Restore for edits, and a one-click link into the IDE (commit, log, tool window, terminal, run,
  build, problems, diff, action).
- **The rules block**: the `<ide-integration>` fragment rides the system prompt and a hook every turn and
  names which tool replaces which native one; each rule is a switch in Settings ▸ Claude Code.

## Board — phase 1, "Claude on JetBrains"

The order of this phase. **Legend**: ☐ to do · ◐ in progress · ☑ done and committed. A row moves in the
same commit that lands its tools, and the tools enter the tables above in that commit. Every domain holds
at most four tools; a capability that needs more is a new domain. Two laws close the surface: every
registered action is reachable through `actions` + `ide_action(target)`, and every action Claude takes is
mirrored in the IDE without taking the user's focus.

### P0 — foundations and the reported bugs

| Capability | Where | Status |
|---|---|---|
| An own call made by a subagent gets its card, nested under the agent | `ToolEvents` | ☑ |
| Permission popup, approval rows and guard log name the tool (`code ▸ read_file ▸ path`), not `run` | `OwnTools.display` on every surface | ☑ |
| An agent still reasoning is never shown as completed | `AgentEnding` | ☑ |
| The rules block has no length limit; the test asserts coverage and prints the size | `IdeMcpPrompt`, `IdeRuleText`, `IdeRule`, `IdeMcpPromptTest`, `IdeRuleCoverageContractTest` | ☑ |
| 250 lines per file, imports not counted | `FileSizeContractTest` | ☑ |
| Reveal without focus on every existing tool; `FocusKeeper` returns the focus the platform steals; the terminal is never focused nor its tab switched | `FocusKeeper`, every domain, `FocusContractTest` | ☑ |
| Live mirror with one switch (Settings ▸ Claude Code, ON): reads in the preview tab, edits in a real tab, commits in the log, nodes in Services, problems in their tab, runs in their window | `Reveal`, `IdeMcpState.mirror`, Settings section | ☑ (build, run, tests and debug are shown by the platform itself, focus-free by default) |
| `vcs_open(log, range)` off the deprecated `openLogTab` | `GitLogNavigator` | ☑ |
| `run_tests(path)` prefers the framework producer over Gradle | `TestTools` | ☑ (when the IDE offers one: with Gradle's *Run tests using: Gradle*, the only producer is Gradle's, and a project with several `Test` tasks may land in the wrong one — run the named configuration then) |

### P1 — every action, with its target

| Capability | Tools (domain) | Status |
|---|---|---|
| The action catalogue of the user's IDE, enabled-in-context; the main menu tree | `actions`, `menu` (`actions`) | ☑ |
| Appearance and UI toggles | `appearance`, `ui` (`actions`) | ☑ |
| `ide_action` with a target: file/position, commit, Services node | `ide_action` +`path`/`line`/`column`/`hash`/`node` (`ide`), `TargetContext` | ☑ |
| Code menu editing actions at a position | `editor_action` (`editor`) | ☑ |
| Undo, redo, replace in path, line operations | `undo`, `redo`, `search_replace`, `line_ops` (`edit_ops`) | ☑ |
| Recent files/locations/changes, back/forward, clipboard compare, schemes | `recent`, `navigate_history`, `compare_clipboard`, `scheme` (`recent`) | ☑ (locations stay out: `IdeDocumentHistory.getBackPlaces` is Internal) |
| Editor tabs, layouts, zoom, editor settings | `tabs`, `layout`, `zoom`, `editor_settings` (`window`) | ☑ |
| Every Git-menu dialog by name | `vcs_action` table extended (`forge`) | ☑ |

### P2 — Git as the log and the branches panel do it

| Capability | Tools (domain) | Status |
|---|---|---|
| The commit context menu on a hash; branch operations through `GitBrancher`; worktrees; remotes | `commit_action`, `branch_op`, `worktrees`, `remotes` (`log_ops`) | ☑ |
| Stash, shelve, patches, rollback | `stash`, `shelve`, `patch`, `rollback` (`changes`) | ☑ (patch apply is the IDE's dialog: `ApplyPatchUtil` is Internal) |
| Blame, file history, local history, a file at a ref | `blame`, `file_history`, `local_history`, `file_at` (`history`) | ☑ (revert uses `Label.revert`, marked Obsolete with no replacement) |

### P3 — pull and merge requests as data

| Capability | Tools (domain) | Status |
|---|---|---|
| GitHub pull requests listed and opened through the IDE's GitHub plugin | `pull_requests`, `pull_request` (`forge`), `GitHubGateway` | ☑ (data through the IDE's account; `open` selects the request in the IDE's Pull Requests view by selecting its row and firing the view's own action, the browser only when the view does not list it) |
| The release driven from the IDE: create, comment, watch and merge pull requests; verify tags, runs, the Release and the Marketplace | `pr_create`, `pr_comment`, `pr_checks`, `pr_merge` (`pull_request_ops`); `tags`, `workflow_runs`, `release`, `marketplace` (`release`); `MarketplaceGateway` | ☑ (GitHub through the IDE's account; the Marketplace through its public API) |
| GitLab merge requests: actions and view; data if a public path exists | `vcs_action`, `GitLabGateway` | ☑ actions only: `GitLabAccountManager` and `GitLabProjectViewModel` are Internal, so no data path exists; `vcs_action(merge_requests, create_merge_request, clone_gitlab, create_snippet, gitlab_accounts)` covers the submenu |

### P4 — analysis, views, files, refactorings

| Capability | Tools (domain) | Status |
|---|---|---|
| Inspect a scope, code cleanup, dependency analysis, data flow | `inspect_scope`, `cleanup`, `file_dependencies`, `dataflow` (`analyze`) | ☑ (backward dependencies is the IDE's window: `BackwardDependenciesBuilder` is Internal) |
| Stack traces, duplicates, nullity, related symbols | `stack_trace`, `duplicates`, `infer_nullity`, `related` (`analysis`) | ☑ (`AnalyzeStacktraceUtil` is Internal: the frames are parsed here and the dialog opens with the text on the clipboard) |
| Diffs, compare, mark directory as, open in | `diff_show`, `compare`, `mark_as`, `open_in` (`views`) | ☑ |
| Copy path, file type, ignore files, delete | `copy_path`, `file_type`, `ignore`, `delete_file` (`files`) | ☑ |
| The Refactor menu beyond rename/move/safe-delete | `introduce`, `extract`, `inline`, `members` (`refactor_ops`) | ☑ |

### P5 — roadmap horizon 2

| Capability | Tools (domain) | Status |
|---|---|---|
| Live and file templates (R1) | `templates`, `template_apply`, `file_templates`, `file_from_template` (`templates`) | ☑ |
| Injected languages, quick documentation (R2; `docs` reveals the popup, `DocumentationTarget` is override-only) | `injections`, `inject_at`, `docs` (`language`) | ☑ (`inject_at` goes through IntelliLang's `TemporaryPlacesRegistry` by reflection in that plugin's loader) |
| Bookmarks and the project view (R3; Structure follows the caret) | `bookmarks`, `bookmark_add`, `bookmark_remove`, `project_view` (`bookmarks`) | ☑ |
| The workspace model, read-only (R4) | `workspace` (`workspace`) | ☑ |
| PSI as a tree (R5) | `psi_tree`, `psi_at`, `psi_replace`, `psi_insert` (`psi`) | ☑ |
| The IDE's indexes (R6) | `index_keys`, `index_query`, `stub_query` (`index`) | ☑ |
| UAST (R7) | `uast_tree`, `uast_at` (`uast`) | ☑ (UAST ships inside the Java plugin: optional dependency `claude-java.xml`, the domain is absent on IDEs without Java) |

### P6 — roadmap horizon 3, presence

| Capability | Tools (domain) | Status |
|---|---|---|
| Editor markup: highlights, gutter icons, inline hints (S1) | `mark_add`, `mark_remove`, `marks`, `hint_add` (`markup`) | ☑ (document markup model + inlays; ids live with the server) |
| Banners, status bar, scratches (S2, S3) | `banner_show`, `banner_clear`, `status`, `scratch_create` (`presence`) | ☑ (`BannerProvider` editor notification + `BannerRegistry` service) |
| One edit, one undo entry, one history label (S4) | `edit` domain | ☑ (every `replace_text`/`insert_text`/`write_file` is one write command and puts a Local History label named after it) |

### P7 — Services in depth, the closed plugins

| Capability | Tools (domain) | Status |
|---|---|---|
| A node's data, extract, expand, events | `service_data`, `service_extract`, `service_expand`, `service_events` (`service_view`) | ☑ (`service_data` reads the editors inside the node's content component; Docker/Kubernetes stay closed, so no deeper data path) |
| Deployment, SSH sessions, Qodana, vulnerable dependencies (S5) | `deployment`, `ssh_session`, `qodana`, `vulnerable_dependencies` (`remote`) | ☑ actions discovered by id fragment on the user's IDE (closed plugins have no source to pin); Qodana results and vulnerable dependencies come from the Problems collector |

### P8 — run, tools, consoles, the client

| Capability | Tools (domain) | Status |
|---|---|---|
| Build a module or a file; run with coverage; more step kinds; watches | `build` +`kind` (module, file), `run_configuration` +`executor` (run, debug, coverage, profile), `step` +`force_into`, `smart_into`, `mute` (`run`, `debug`) | ☑ except watches: the watches model (`XDebugSessionData`, the watches manager) is Internal at 262, so `values(expression)` is the evaluation path |
| Edit configurations, attach, profile, coverage | `edit_configuration`, `attach`, `coverage` (`run_ops`); profile = `run_configuration(executor=profile)` | ☑ |
| Terminal tabs | `terminal_tabs` (`terminal`) | ☑ |
| Javadoc, launchers, XML, Markdown | `javadoc`, `launcher`, `xml`, `markdown` (`tools_menu`) | ☑ (dialogs the user finishes; javadoc headless needs the Java plugin's `JavadocGeneratorRunProfile`, which is not public) |
| Groovy console, Kotlin bytecode and configuration, Python console | `groovy_console`, `kotlin_bytecode`, `kotlin_configure`, `python_console` (`consoles`) | ☑ by action, discovered where the Kotlin and Python ids are plugin-defined; the bytecode text stays in the IDE's panel (no public document accessor) |
| Any MCP client drives the IDE (Q10); split mode (S6) | `McpClient` (test source) + `McpClientHeadlessTest`, [`MCP_CLIENT.md`](MCP_CLIENT.md); split mode = the chat page failing to arrive, then the servers start without a chat and the notification carries the MCP configuration | ☑ |

**Out, on record**: `completion` (its parameters have no public constructor), LSP (commercial IDEs; runtime
detection if ever needed), `sdk_set` (a global change that goes through the dialog), `structure_select`
(no public accessor; the Structure window follows the caret), and the third-party server tools discarded in
the roadmap's coverage matrix — change signature as data, super methods, structural search and replace,
module and project lifecycle, plugin install, IDE restart.
