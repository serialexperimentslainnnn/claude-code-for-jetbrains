# MCP roadmap — sprint board

A living document. `DIRECTIVES.md` says **what** is decided and **why**; this says **in what order**, with
**every task**, and **what is done**. It is updated in the same turn the state changes.

**Legend**: ☐ to do · ◐ in progress · ☑ done and committed.

**Sprint exit**: green on `Tool: tests kotlin`, `Tool: tests frontend` and `Tool: lint`, read from the tail
of `.claudetools/run/out/<name>.log`; clean diagnostics on every file touched; a signed commit.

**Every API named below was read from the platform source at `idea/253.29346.138`.** Where a task says an
API is forbidden, the replacement is named. Nothing here is written from memory.

**Two rules that apply to every sprint.** At most **four tools per domain** — a domain that does not fit is
split. And every tool returns the smallest answer that settles the question, with a hard `limit` on anything
that enumerates.

---

## Sprint 0 — the floor

First, because everything else rests on the compiler telling the truth.

- ☑ `sinceBuild` pinned to the current stable. Already `253.29346.138`, `untilBuild` `263.*`.
- ☑ `allWarningsAsErrors = true` in `build.gradle.kts`. A deprecated API emits a warning and
  `@ApiStatus.Experimental` does not, so this forbids exactly what is forbidden and tolerates exactly what
  is tolerated, with no list to maintain.
- ☑ `FileRollback` off the two-argument `runWriteCommandAction`, which is `@TestOnly` and names the undo
  entry `Undefined`, onto `writeCommandAction(project).withName(…)`.
- ☑ `TerminalLauncher` off the reflected five-argument `createNewSession`, which is `@ApiStatus.Internal`,
  onto the public `createShellWidget(workingDirectory, tabName, requestFocus, deferSessionStartUntilUiShown)`
  plus `TerminalWidget.sendCommandToExecute(String)`.
- ☑ The terminal contract test stops pinning the reflected signature and pins the public one, and asserts
  reflection does not come back.
- ☑ `InternalPlatformApiContractTest`: scans production sources for the internal symbols the compiler cannot
  flag, each with its public replacement named in the failure message.
- ☐ Coverage thresholds reviewed for the packages about to appear. Carried into sprint 1, because there is
  nothing to measure until the packages exist.

**Commit**: ☑ `build: warnings are errors, and no internal platform API gets in`

## Sprint 1 — the third parties go

- ☐ `IdeServer` becomes `CODE`, `RUN`, `VCS`, `OPS`, `JETBRAINS`. The two hechtcarmel entries, their plugin
  ids, ports, install buttons and per-server rules are deleted.
- ☐ Delete `IdeRule.kt` and the `knownTools` field. The rules existed to describe someone else's tools.
- ☐ `IdeMcpPrompt` shrinks to one paragraph: the four servers, what each is for, and that you start with
  `domains()`. The injected block names no tool at all, and its character ceiling is tightened, not relaxed.
- ☐ `GodMode` is our servers plus the common rules. The JetBrains server is not part of it.
- ☐ `IdeMcpState` loses the third-party ports and flags, tolerantly parsing old persisted JSON.
- ☐ `SettingsIdeMcpSection`: one checkbox for our integration, with the JetBrains server kept as an optional
  extra behind `IdeServerControls`.
- ☐ `McpConfigBuilder` emits four stdio entries. `LaunchOptions` replaces the index/debugger flags.
- ☐ `SessionLauncher` and `JcefSettingsMenu` follow.
- ☐ `PluginInstallerTest` stops naming the removed servers; README and the settings documentation follow in
  the same commit.

**Commit**: `refactor(mcp): the IDE integration is servers of our own; the third-party servers are gone`

## Sprint 2 — TOON, and a round trip that works

The delicate one. The codec lands before any tool does, because everything downstream is measured in it.

- ☐ A TOON codec twice: Kotlin in the pure core, and dependency-free Java for the helper, which is launched
  by a bare `java` and cannot see Kotlin.
- ☐ All four forms: inline, list, tabular and keyed tabular, plus nested field groups. Tabular is where the
  saving is and is the shape of nearly everything returned.
- ☐ Validated against the specification's **published reference fixtures**, not invented examples, plus a
  round-trip test of our own.
- ☐ `JsonRpc`, `ToolSpec`, `ToolArgs`, `ToolResult`, `OutputBudget` in the pure core, which joins the
  platform-free package list and therefore may not name `com.intellij`.
- ☐ A dual-era `McpServer`: modern fields always emitted, branching only on `initialize` versus
  `server/discover`. One server instance per catalogue, not four implementations.
- ☐ `StdioBridge`: first line of stdin is the credential, then JSON-RPC; translates to TOON at the boundary
  and pumps against the socket. Its jar path resolves from its own code source location.
- ☐ Four Unix sockets under a 0700 directory named with 128 random bits, removed on dispose. No TCP.
- ☐ The three meta-tools, with the test that `tools/list` returns exactly three entries per server.
- ☐ A queue per server: the socket reader never executes a tool; immediate acknowledgement; replies
  correlated by `id` and therefore allowed out of order; timeout and cancellation on every tool; a bounded
  depth that refuses with an actionable message instead of growing.
- ☐ The auth token: generated in code, handed to each server at `init`, kept in memory, carried in the
  per-request metadata the specification reserves, rotated every 30 minutes with a short overlap so
  in-flight requests survive. Tests: a missing or expired token is refused without saying why, the secret
  never reaches a log or an error, and it is not in the helper process environment.
- ☐ A notification when a connection arrives that is not a known chat tab, with a setting to require
  approval. In the hacked-developer scenario this is worth more than the token.
- ☐ The guard evaluated at the `run(tool, args)` dispatcher — one place, not twenty-six — with a test that a
  dangerous **nested** argument is refused. Nothing under the permission package is touched, and nothing
  needs to be: the classifier never sees a tool name and the input scanner already recurses.
- ☐ Every tool names its parameters with the keys the guard recognises, so they inherit every existing
  verdict rather than needing new rules.
- ☐ The permission card reads the inner tool name from the dispatcher's argument, so the user sees
  `read_file` rather than `run`. That is view work, not guard work.
- ☐ Domains `read` and `search` of the `code` server. `read_file` resolves through the local file system and
  reads text via the immutable character sequence, which needs no lock. `search_text` goes through the IDE's
  find-in-project machinery with a model carrying the query, regex, case and file mask. `find_files` uses the
  filename index, and falls back to iterating content with the exclusion check for globs; case-insensitive
  name lookup is documented as a full scan.

**Commit**: `feat(mcp): four MCP servers inside the plugin, each asked for its tools on demand`

## Sprint 3 — the code as the IDE understands it

- ☐ `definition` and `symbol_info`: the PSI file for the document, the reference at the offset, resolved.
  Coordinates validated before use so a bad line is an actionable error, not an exception.
- ☐ `references`: the reference search query, consumed by a processor rather than an iterator, because the
  iterator forms are deprecated.
- ☐ `implementations`: the definitions-scoped search.
- ☐ `call_hierarchy` and `type_hierarchy`, grouped under one tool with a direction argument so the domain
  stays within four.
- ☐ `find_symbols` through the symbol and class contributor extension points, processing names and then
  elements. Not the short-names cache, which would tie the plugin to Java.
- ☐ `file_outline` from the structure view builder for the file's language, walked as a tree.
- ☐ `problems`: the daemon's already-computed highlights, iterated under the markup lock with no heavy work
  inside the processor. The file has to be open for this to be meaningful, and the tool says so.
- ☐ `project_problems`: the problems collector, whose listener methods are expected on the UI thread.
- ☐ `inspect`: running an inspection on a file through the public engine entry point, with the current
  profile and a new global context. This does not depend on the daemon, which is what covers the gap left by
  the highlight path being marked for future deprecation.

**Commit**: `feat(mcp): navigation, the file outline, the IDE's problems and its inspections`

## Sprint 4 — writing, and one undo entry

- ☐ Every write wrapped in a named command, so the user's undo says what Claude did. Several files in one
  command by marking it global and declaring the affected files, which is required when touching the virtual
  file system rather than a document.
- ☐ A single local-history label around the same span, started and finished in a `finally`. The history
  service never returns null; availability is checked with its enabled flag, not a null comparison.
- ☐ The virtual-file-system requestor is **never null**, because null is interpreted as an external change
  and detaches our edits from the history that groups them.
- ☐ `replace_text`, `insert_at`, `create_file` through the document API and the directory creation utility.
- ☐ `rename` through the refactoring factory, set non-interactive so it never opens a dialog.
- ☐ `move_file` through the move processor, because the refactoring factory has no move.
- ☐ `safe_delete` through the safe-delete processor.
- ☐ `reformat` and import optimisation through the code style manager and the optimise-imports processor.
- ☐ `quick_fixes` and `quick_fix_apply`: the intentions pass collects actions, cached intentions expose them,
  and application happens on the UI thread inside a write command when the action asks for one. The offset
  comes from the caret.
- ☐ Editor state: selected editor, caret movement and selection, all on the UI thread and outside a write
  action.
- ☐ Every edit opens the existing review diff, reusing the plugin's own diff opener.

**Commit**: `feat(mcp): edits, refactors, quick fixes and formatting, each opening the review diff`

## Sprint 5 — building, testing, running

- ☐ `build` through the project task manager. Its result reports only aborted and has-errors, so the error
  list comes from the problems collector. The build-view problems service does not exist in this platform.
- ☐ `run_configurations` listed from the run manager; `run_configuration` executed through an execution
  environment built for the run executor, with the process listener attached **before** notification starts,
  the exit code taken from the process event, and system output filtered out.
- ☐ `run_tests`: there is no "run this test and give me a verdict" API. The run configuration is executed and
  the public test-status topic is listened to. The test proxy is documented as UI-thread-only, so it is
  copied into an immutable value inside that thread. The declarative status-listener extension point is not
  usable for this.
- ☐ `shell`: a command line with a coloured process handler, attached to a terminal execution console in the
  tool window, keyed on the content so a tab is reused. This is what the platform itself does, and it gives
  reliable output and exit codes. The interactive tab stays on the public shell widget.
- ☐ Long work reports through background progress rather than going silent and dumping at the end.

**Commit**: `feat(mcp): builds, tests, run configurations and a shell in the IDE's terminal`

## Sprint 6 — the debugger

Nine drafted tools do not fit in four, so the domain is split into `debug` and `breakpoints`, and stepping
becomes one tool with a kind argument.

- ☐ `debug_start`, `debug_status`, `debug_step(kind)`, `debug_stop` in one domain.
- ☐ `breakpoint_set`, `breakpoint_remove`, `breakpoints`, plus evaluation, in the other.
- ☐ Breakpoints are added by **file URL**, not by virtual file, and the deprecated single-breakpoint lookup
  is replaced by the plural one.
- ☐ Waiting for a pause is a session listener callback, **never polling**, and suspension is checked **after**
  subscribing to close the race.
- ☐ Evaluation and variables through the evaluator and stack frame, copied out into values before crossing a
  thread boundary.

**Commit**: `feat(mcp): the debugger as tools`

## Sprint 7 — git and the forge

- ☐ Reading stays in the existing git gateway, the only file allowed to name the git plugin's types, and the
  read-only contract test that pins that stays green.
- ☐ Writing lives **outside** that package, because the contract test scans it: commit, pull, push, fetch,
  merge, rebase, tag, reset and stash through the line handler, with the message written via the public
  commit-message file helper.
- ☐ Branches through the brancher, using the reference overload, not the deprecated string one.
- ☐ Fetch through the fetch support, which is synchronous and blocking and therefore never called on the UI
  thread.
- ☐ Push through the git facade, because the push support has a private constructor.
- ☐ Status for any version control system through the change list manager, with its asynchronous update
  callback, because the state is not immediately current.
- ☐ The forge is **not** a command-line wrapper: the IDE's own git actions are invoked by id and its tool
  window activated, reusing the plugin's existing navigator and action invoker.

**Commit**: `feat(mcp): git, and the forge through the IDE's own views`

## Sprint 8 — the Services panel

The DevOps piece, and the reason no container or cluster command line is wrapped anywhere in this roadmap.

- ☐ `services()`: the tree as the user sees it, walked recursively through the service view contributor
  extension point.
- ☐ `service_actions(path)`: the descriptor for a node exposes its toolbar and popup action groups, so the
  actions the IDE itself offers on that node can be enumerated.
- ☐ `service_action(path, action)`: invoked through the existing action invoker, passing the guard.
- ☐ `service_open(path)`: select the node and activate the tool window.
- ☐ Honest limitation recorded in the tool descriptions: presentation and actions are available, typed domain
  objects are not, and no supported path to them is invented.

**Commit**: `feat(mcp): the Services panel, and every action the IDE offers on its nodes`

## Sprint 9 — databases, HTTP, SSH

The fragile sprint. Each domain degrades to absent rather than breaking startup.

- ☐ All database internals in a **single gateway file**, reached by reflection from the context class loader,
  wrapped so a changed signature degrades the domain instead of throwing. A contract test fails if any file
  outside that package names the database plugin.
- ☐ Recorded in the gateway's tests: the data-source classes moved in a recent release, the official thread
  about it went unanswered, and the extension-point documentation for that plugin is a dead link. There is no
  contract, which is exactly why it is isolated.
- ☐ Decide whether the SQL argument is renamed so it inherits the guard's command verdict. Today it would not.
- ☐ HTTP through the IDE's own request run configuration, located at runtime by configuration type because
  its id is undocumented and the plugin is not in Community. Absent plugin means absent domain, and the
  message names the plugin. The command-line HTTP client is **not** wrapped.
- ☐ SSH read-only through the credential provider extension point, which is the supported way to enumerate
  what the IDE knows. Executing over SSH would be an external process and stays out.

**Commit**: `feat(mcp): the IDE's databases and HTTP client as tools`

## Sprint 10 — Claude configures the IDE it works in

- ☐ `ide_action(id)`: the existing action invoker moves into the MCP package, which gives reach to **every
  registered action in the IDE**, with enablement and visibility checked before performing.
- ☐ `tool_window(id)`: activation by id, with the verified ids for Services, Problems, Version Control,
  Commit, Database, Run, Debug, Project and Structure. Terminal is not among the platform constants.
- ☐ `settings_open(name)` plus the typed settings services for the editor, the general settings, the user
  interface — refreshing it after mutation — and code style. There is **no generic set-by-key writer**; the
  model that would offer one is internal, so none is faked.
- ☐ `modules`, dependency changes through the root-modification utility the javadoc recommends, and SDKs
  through the JDK table and project root manager, both of which genuinely require a write lock.
- ☐ `plugins()` from the plugin manager.
- ☐ `notify`: a notification group **registered in the plugin descriptor**, with actions rather than the
  deprecated listener, and no links in the content, as the javadoc asks.
- ☐ `index_status`: dumb-mode state, knowing that running when smart does not block and waiting does.

**Commit**: `feat(mcp): Claude configures the IDE it works in, and speaks up inside it`

## Sprint 11 — any MCP client

- ☐ The core knows nothing about Claude Code. Everything specific to it — the launch configuration, the
  project directory variable, its output ceilings — lives in one adapter behind a setting.
- ☐ The connection configuration is published so another client can read it, behind a setting that enables it.
- ☐ Recorded as debt: a minimal MCP client in the repository is the only thing that would actually prove the
  claim. It is not in this release.

**Commit**: `feat(mcp): any MCP client can drive the IDE`

## Sprint 12 — the plugin passphrase

A passphrase for the **plugin**, not for the IDE: entered when it opens, held **in memory only**, and used to
encrypt everything the plugin stores. It is also what arms the MCP servers.

- ☐ The envelope goes into the single function pair that touches the credential store, which is the only
  place in the plugin that does, so every consumer is covered without being edited.
- ☐ What that protects, and why it is worth it: the authentication token, the API key, the credentials blob,
  the account profile and status, the environment variables, the settings blob, the open chats, the agent
  index, the review prompt, and the **guard log**.
- ☐ **No home-made cryptography.** Key derivation with a standard password-based function from the runtime,
  a random salt and a high iteration count; encryption with authenticated symmetric encryption and a fresh
  random nonce per record, so a tampered record is detected.
- ☐ A versioned record format, so the scheme can change without orphaning what is stored.
- ☐ The salt is not secret and lives in ordinary settings. The derived key lives only in memory, is cleared
  when the project closes or the passphrase is disarmed, and its buffers are wiped after use.
- ☐ A verifier record, so a wrong passphrase is detected without decrypting everything else.
- ☐ **The same passphrase arms the servers.** They are born disarmed and are disarmed by hand. No passphrase,
  no key; no key, no token; no token, the sockets answer nobody. That is what actually stops the attacker in
  the hacked-developer scenario.
- ☐ Migration both ways: enabling re-encrypts what exists, disabling decrypts it. A wrong passphrase
  **refuses and changes nothing** — never wipe because you could not read.
- ☐ Tolerate the platform's known behaviour of emptying the store on a password-token mismatch: a secret may
  simply be gone, and what can be regenerated is regenerated.
- ☐ Forgetting the passphrase loses what was encrypted. There is no recovery and none is invented. It is said
  plainly when the feature is turned on, alongside the fact that most of it is recoverable by signing in
  again — what is genuinely lost is the guard log's history.
- ☐ This is new security code, not a change to the guard. It ships with its own tests and touches nothing
  under the permission package.

**Commit**: `feat(security): a passphrase of the plugin's own encrypts what it stores and arms the servers`

## Sprint 13 — the release face

- ☐ The first-run tutorial on the flame, reworded: one switch, nothing to install.
- ☐ Version text across the changelog, the release notes and the README.
- ☐ Manual pass: God Mode on, a fresh chat, the card lists four servers with three tools each, a read goes
  `domains()` then `tools("read")` then `run("read_file", …)`, the permission card shows the inner tool name,
  an edit opens the review diff, a container is stopped and started from the chat through the Services panel,
  and no new listening port appears.

**Commit**: the tutorial and the version text

---

## Horizon 2 — the code as a program

Already researched; becomes sprints once the release ships. Same standard: no signature written without
having been read.

| Area | Tools | Verified ground |
|---|---|---|
| Templates | `templates`, `template_apply`, `file_templates`, `file_from_template` | Live template settings and manager; file templates and the creation utility. Risk: the live-template settings classes live in an implementation package |
| Injected languages and documentation | `injections`, `inject_at`, `docs` | The injected language manager is fully public core API. Documentation goes through the modern target provider, not the older provider whose javadoc already asks for migration |
| Bookmarks and the project view | `bookmarks`, `bookmark_add`, `project_view_select`, `structure_select` | The bookmarks manager is experimental and therefore tolerated; the project view selects and changes panes. All UI thread |
| Workspace model, read-only | `workspace` | The current snapshot is documented as readable without locks from any thread — the cheapest read available. Updating the project model through it is obsolete; writes stay on the root-modification utility |
| PSI as a tree | `psi_tree`, `psi_at`, `psi_replace`, `psi_insert` | Create through the file factory with an explicit language on an in-memory file and graft the subtree; mutate with the element add/replace/delete API; navigate with the tree utility; whitespace and comments language-agnostically through the parser facade. Pattern matching is a predicate for extension points, not a finder |
| The indexes | `index_keys`, `index_query`, `stub_query` | The file-based index is an application service; stub elements come from the plural getter. Other plugins' indexes **are** queryable because index ids resolve by name globally; the limit is the class loader. Real trap: several overloads silently discard the id filter, and one throws on a scope without a project |
| UAST | `uast_tree`, `uast_at` | Present in Community in this build. The facade is a Kotlin object plus extension functions. **Read-only**: mutation goes through the underlying source PSI |

**Conditional, and may never ship**: a completion tool. The service entry point is public, but the
convenient path is test-framework only and the progress indicator is internal. If there is no stable public
way to build completion parameters, it is not done — a tool that needs internal API to exist fails the policy.

**Out of scope, documented**: language server integration. That module is not in Community and the official
documentation confirms it is a commercial-IDE extension, so such a domain would not start at all.

## Horizon 3 — presence in the IDE

This is what makes Claude *visible* in the IDE rather than only active in it, and it is what sustains the
proactive working style the plugin is for.

| Area | Tools | Verified ground |
|---|---|---|
| Editor markup | `mark_add`, `mark_remove`, `marks`, `hint_add` | Range and line highlighters taken with the **colour-scheme key** overloads, which is what survives a theme change; gutter icons through the highlighter's renderer, whose base class declares equality and hashing abstract, so they must be implemented; inline, block and after-line-end hints through the inlay model, added in batch mode and removed by disposal |
| The editor banner | `banner_show`, `banner_clear` | Only through the project-level notification provider extension point, whose single method runs **under a read lock, off the UI thread** and returns a function the platform applies on the UI thread. Refresh through the notifications service; the older nested provider class and the per-provider refresh are deprecated |
| Diffs, scratches, the status bar | `diff_show`, `scratch_create`, `status` | Diff contents from the content factory, including the **empty content** that represents a created or deleted file, wrapped in a simple request and shown on the UI thread; a chain for several files. Scratch files are one call, and the platform already wraps it in a global-undo write command, so it must be called where a write action is legal. Status text through the window manager, with a fixed widget requiring the widget factory extension point |
| Split and remote IDE | — | **Not yet considered, and a real risk.** With a split frontend and backend, a local Unix socket may not be where the client believes. The remote-procedure and shared-API documentation is read before anything is promised |
| Other products | — | Databases with a supported API if one ever appears, plus the framework integrations, each as a degradable domain |

Three pieces from this horizon that earlier sprints already need: background progress is the modern
suspending API and exists in this build, so the blocking progress manager is legacy and the project-first
asynchronous overload is scheduled for removal and banned; the file chooser factory moved and nearly all its
create methods are obsolete, so the short names are used; and a tool window of our own adds content through
the content manager, never by injecting components into the window's component.

## What this drags along

**Files rewritten**: the server enumeration, the injected prompt, the God Mode predicate, the launch
configuration builder, the session launcher, the launch options, the integration's persisted state, its
settings section, and the settings menu in the page.

**Files deleted**: the per-server rule type and the known-tools inventory field.

**Reused as they are**: the plugin installer and its server controls, now serving only the JetBrains server;
the single UI-thread helper; the installed-plugin lookup; the plugin id helper; the wire JSON model; the git
history service; the action invoker; the forge navigator; the review diff opener; and the anchored allowlist
pattern used by the vulnerability-prompted actions, including its per-token and cardinality ceilings.

**Gates that move with it**: the package dependency contract gains constants for the two new packages and
their allowed edges, with the pure core added to the platform-free list; the file size ceiling applies as
everywhere else; the no-comments and reachability contracts apply; and the coverage floors need their
exclusions reviewed **before** the first MCP sprint, because the controller package will be IDE-bound and
therefore hard to unit-test at the same level as pure code.

**Capability degradation**: a domain whose plugin is missing does not appear in its server's domain list at
all, and a server with no live domains is not started. Discovery is by plugin id against the verified list
for Docker, Kubernetes, the database plugin, the HTTP client, the configuration-language plugin, the cloud
toolkit, the JetBrains MCP server, the terminal and git.

## The tests that define the design

- The TOON codec against the specification's **published fixtures**, plus a round trip of our own.
- One-line framing with a payload containing an escaped newline.
- The same server answering a legacy handshake and a modern discovery.
- An unknown tool answered as a protocol error; a failed execution answered as a tool error with actionable
  text, never an escaping exception.
- Truncation that announces itself.
- A stable order in the tool listing.
- The four sockets removed on dispose.
- No tool present in two catalogues; a server with no live domains does not start.
- **A dangerous input inside a nested argument of the dispatcher is refused by the guard.**
- No file outside the database gateway names the database plugin; no file in the git read package gains a
  write API.
- **`tools/list` returns exactly three entries per server**, the injected block names no tool, and **no domain
  exceeds four tools**. If any of those grows, on-demand discovery has broken.

## Open decisions

- The exact split of domains under the four-tool ceiling, taken at the start of each sprint.
- Whether the SQL argument is renamed so it inherits the guard's existing verdict.
- A stronger key derivation for the plugin passphrase, which would add a dependency and is decided separately.
- Whether a completion tool is viable at all.
