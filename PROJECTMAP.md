# Map of Claude Code Native

> Generated 2026-08-14 against `8933592`. **The whole 5.5.0 release is uncommitted**, so the working tree is
> ahead of that SHA and `git ls-files` alone misses most of it — `git status` is the authority on what
> changed. If anything here contradicts the repo, **the repo wins**: fix the line and move on.
>
> This is the **root** of a distributed map. Every substantial directory carries its own `PROJECTMAP.md` with
> the symbols that live there, generated from the code. Read this file, then the local map of the directory
> you are working in — never all of them.
>
> This file says **where** things are. How work is done here lives in [`CLAUDE.md`](CLAUDE.md) and is not
> repeated. Nothing here records the result of a measurement: the Commands table gives you the command, and
> the answer is whatever it prints today.
>
> **Path shorthand**: a path written `session/…`, `ui/…`, `protocol/…` and so on is relative to
> `src/main/kotlin/dev/lain/claudejb/`; anything else is relative to the repository root. Inside a local map,
> a bare filename is relative to that map's own directory. So a path in a map resolves against one of those
> three roots, and a path that resolves against none of them is dead.

An IntelliJ Platform plugin (Kotlin/JVM) that speaks the `stream-json` + control protocol **directly** to the
`claude` binary and renders the conversation in an embedded Chromium view. No Node and no TS SDK at runtime.
Everything visible is JCEF; Swing survives only where the platform forces it — tool window, menus, dialogs —
and diffs, which stay native through the IDE's `DiffManager`.

## I want to change… → go to…

| To… | Go to | Note |
|---|---|---|
| Anything the model says or the user sees | `src/main/resources/jcef/` → [map](src/main/resources/jcef/PROJECTMAP.md) | The UI is a web app. **Never add Swing.** |
| A style | `src/main/resources/jcef/css/` → [map](src/main/resources/jcef/PROJECTMAP.md) | Concatenated in **cascade order** by `JcefHost.CSS_PARTS`; a new part goes where it belongs, never at the end |
| Handle a new binary→host event | `protocol/` → [map](src/main/kotlin/dev/lain/claudejb/protocol/PROJECTMAP.md) | Add the sealed case, its model file and a row in `ProtocolParser`'s decoder tables — **register, don't add an `if`** — then triage the subtype into `ProtocolSurface.KNOWN_SUBTYPES` or `checkDrift` goes red |
| Ask the binary something | `session/ControlAsks.kt` → [map](src/main/kotlin/dev/lain/claudejb/session/PROJECTMAP.md) | One declared `Ask` in the `Asks` catalogue; `SessionQueries` owns the plumbing. **Wire a caller in the same change** — a request nothing sends is this repo's signature defect |
| How a turn is orchestrated | `session/ClaudeSession.kt` → [map](src/main/kotlin/dev/lain/claudejb/session/PROJECTMAP.md) | **Thin orchestrator**, and the most-churned file in the repo. New behaviour goes to a collaborator |
| Transcript rows and streaming | `session/TranscriptReconciler.kt` + `session/TranscriptModel.kt` | Assumes EDT. `parentOf`/`isDescendantOf` model subagent nesting |
| What the chat renders | `resources/jcef/app-*.js` → [map](src/main/resources/jcef/PROJECTMAP.md) | No bundler. **Load order is a contract** (`JcefHost.appNames`); CSS class names are a tested contract |
| A field in the web payload | `ui/jcef/Jcef*Data.kt` → [map](src/main/kotlin/dev/lain/claudejb/ui/jcef/PROJECTMAP.md) | kotlinx `buildJsonObject`, null-safe so a card omits cleanly. Running/finished states use `JcefStatus` |
| A web→host message | `ui/jcef/JcefBridge.kt` (pure parse) + `ui/ChatBridgeRouter.kt` (dispatch) | `JcefBridge` has no IDE deps, so it unit-tests on a plain JVM |
| Tabs, tool window or the ⚙ menu | `ui/` → [map](src/main/kotlin/dev/lain/claudejb/ui/PROJECTMAP.md) | The tab bar is drawn by the **web** app, not by Swing; `ChatTabsPanel` holds chats and draws nothing |
| Permission behaviour | `permission/` → [map](src/main/kotlin/dev/lain/claudejb/permission/PROJECTMAP.md) | `SensitiveGuard` runs **before** any auto-approval and has no opt-out. A new rule is a file, not a branch |
| What a diff shows, or how an edit is undone | `diff/` → [map](src/main/kotlin/dev/lain/claudejb/diff/PROJECTMAP.md) | The **binary** writes the file; the IDE reviews and refreshes the VFS. Restore lives on the transcript card |
| Review everything the session changed | `ui/SessionDiffAction.kt` → `session/WorkspaceDiffReview.kt` → `diff/DiffPresenter.openTextDiff` | The binary sends hunks; the base side is reconstructed and **refused** when it does not match disk |
| Launch flags or the system prompt | `session/SessionLauncher.kt`, `process/PluginContextPrompt.kt`, `settings/SettingsLaunchEnv.kt` | Immutable `LaunchOptions` snapshot; `--print` is mandatory |
| Auth or credentials | `session/AuthGate.kt`, `process/` → [map](src/main/kotlin/dev/lain/claudejb/process/PROJECTMAP.md), `settings/SecretStore.kt` | Credentials reach the binary **by env only** — never argv, never logs, never the transcript |
| Read a past session | `session/SessionStore.kt` + `session/SessionTranscriptReader.kt` | The binary's files are the source of truth; the plugin persists no transcripts |
| The agent tree or background tasks | `session/AgentRegistry.kt`, `session/BackgroundTaskRegistry.kt` → [map](src/main/kotlin/dev/lain/claudejb/session/PROJECTMAP.md) | The **bare** agent id is the identity — `agent-<id>.jsonl` is a filename, not an id |
| A setting | `settings/ClaudeSettings.kt` + the owning `ui/Settings<X>Section.kt` | Persisted into the **PasswordSafe**; mutate through `update {}` or it does not survive a restart |
| Anything Git | READ `git/` → [map](src/main/kotlin/dev/lain/claudejb/git/PROJECTMAP.md) · WRITE `ui/GitPromptedActions.kt` · HAND-OFF `ui/GitIdeMenu.kt` | **Reads are the plugin's, writes are asked of Claude, dialogs are the IDE's** — which is what keeps `git/` read-only by construction |
| Attachments, @-mentions, clipboard | `context/` → [map](src/main/kotlin/dev/lain/claudejb/context/PROJECTMAP.md) | The pure halves are split out so they can be tested; only the process spawn is not |
| An editor action or context-menu item | `src/main/kotlin/dev/lain/claudejb/actions/` | Registered in `src/main/resources/META-INF/plugin.xml`; the action classes are listed there, not counted here |
| A JVM test | `src/test/kotlin/` → [map](src/test/kotlin/dev/lain/claudejb/PROJECTMAP.md) | Unit · contract · headless · integration, all inside the one `test` task |
| A frontend test | `src/test/frontend/` → [map](src/test/frontend/PROJECTMAP.md) | vitest + jsdom over the **real** inlined modules |
| Compatibility range, dependencies, gates | `build.gradle.kts` | `version` is the single source of truth the release workflow greps |
| CI, the release or branch protection | `.github/` | Merging to `main` publishes to the Marketplace unattended |
| A policy or procedure document | `docs/` → [map](docs/PROJECTMAP.md) | ADRs under `docs/adr/` |

## Tree — the whole repository at a glance

```
.
├── build.gradle.kts          compatibility range, dependencies, every gate, the custom test tasks
├── package.json              frontend tooling only — nothing here ships inside the plugin
├── bin/fake-claude           deterministic Python stand-in for the binary; drives the integration suite
├── config/detekt/            detekt configuration and its baseline
├── docs/                     → docs/PROJECTMAP.md
│   └── adr/                  architecture decision records
├── scripts/                  generators and operational one-offs
├── src/main/
│   ├── kotlin/dev/lain/claudejb/
│   │   ├── actions/          editor and context-menu AnActions, registered in plugin.xml
│   │   ├── context/          → src/main/kotlin/dev/lain/claudejb/context/PROJECTMAP.md
│   │   ├── diff/             → src/main/kotlin/dev/lain/claudejb/diff/PROJECTMAP.md
│   │   ├── git/              → src/main/kotlin/dev/lain/claudejb/git/PROJECTMAP.md
│   │   ├── permission/       → src/main/kotlin/dev/lain/claudejb/permission/PROJECTMAP.md
│   │   ├── process/          → src/main/kotlin/dev/lain/claudejb/process/PROJECTMAP.md
│   │   ├── protocol/         → src/main/kotlin/dev/lain/claudejb/protocol/PROJECTMAP.md
│   │   ├── session/          → src/main/kotlin/dev/lain/claudejb/session/PROJECTMAP.md
│   │   ├── settings/         → src/main/kotlin/dev/lain/claudejb/settings/PROJECTMAP.md
│   │   ├── ui/               → src/main/kotlin/dev/lain/claudejb/ui/PROJECTMAP.md
│   │   │   └── jcef/         → src/main/kotlin/dev/lain/claudejb/ui/jcef/PROJECTMAP.md
│   │   └── util/             a plugin lookup that survives the PluginId Kotlin migration
│   └── resources/
│       ├── META-INF/         plugin.xml, the optional-dependency descriptors, licences and notices
│       └── jcef/             → src/main/resources/jcef/PROJECTMAP.md   ← the entire user interface
├── src/test/
│   ├── kotlin/               → src/test/kotlin/dev/lain/claudejb/PROJECTMAP.md
│   ├── frontend/             → src/test/frontend/PROJECTMAP.md
│   └── resources/            JSONL fixtures the integration suite replays
└── src/uiTest/               → src/uiTest/PROJECTMAP.md   (RemoteRobot, gated, outside `check`)
```

## Subdirectories — what each one holds

| Directory | What lives there | Local map |
|---|---|---|
| `src/main/kotlin/dev/lain/claudejb/process/` | Locating, launching and authenticating the `claude` binary: the locator, the process, the credential vault, the login PTY. | [map](src/main/kotlin/dev/lain/claudejb/process/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/protocol/` | The wire: NDJSON models, the table-driven parser, the control-frame builders. **No IDE dependencies** — it unit-tests on a plain JVM. | [map](src/main/kotlin/dev/lain/claudejb/protocol/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/session/` | One `ClaudeSession` per chat tab plus its single-responsibility collaborators: turn plumbing, transcript narration, identity, the agent tree, background tasks, the history readers. The largest package — the local map is how you avoid reading it whole. | [map](src/main/kotlin/dev/lain/claudejb/session/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/permission/` | What may run without asking: the `can_use_tool` broker plus the deterministic sensitive-data lock, split one file per seam. | [map](src/main/kotlin/dev/lain/claudejb/permission/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/diff/` | Presenting a proposed edit for review, selecting hunks, snapshotting and rolling back an applied one. | [map](src/main/kotlin/dev/lain/claudejb/diff/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/git/` | Read-only Git context. `GitGateway` is the only file naming a `git4idea` type in code, so an unsatisfied optional dependency degrades to "no Git surface". | [map](src/main/kotlin/dev/lain/claudejb/git/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/context/` | What the user can attach: @-mentions, files, selections, images, clipboard. | [map](src/main/kotlin/dev/lain/claudejb/context/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/settings/` | Persistence: one serialized document in the IDE PasswordSafe, the launch env derived from it, and the one-shot adoption of the legacy project file. | [map](src/main/kotlin/dev/lain/claudejb/settings/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/ui/` | Everything Swing is still allowed to be — tool window, tabs, menus, dialogs, the Settings page — plus the assembler that drives the web view and its collaborators. | [map](src/main/kotlin/dev/lain/claudejb/ui/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/ui/jcef/` | The host side of the web view: the browser and page delivery, the pure bridge, and one JSON builder per card. | [map](src/main/kotlin/dev/lain/claudejb/ui/jcef/PROJECTMAP.md) |
| `src/main/kotlin/dev/lain/claudejb/actions/` | `AddFileAsContextAction`, `AddSelectionAsContextAction`, `ExplainSelectionAction`, and `AttachmentActions` behind them. They reach a chat through `ClaudeToolWindowFactory.activePanel(project)` and never cast the tool window's content themselves — it holds ONE `Content`, and its component is the strip. | — |
| `src/main/kotlin/dev/lain/claudejb/util/` | `InstalledPlugins` — reads a plugin id off its descriptor, because `PluginId.getId(…)` is banned (see Minefields). | — |
| `src/main/resources/jcef/` | **The whole user interface**: `shell.html`, the `app-*.js` modules in an order that is a contract, the `css/` parts, and the vendored libraries. Served as ONE document under a hash-pinned CSP. | [map](src/main/resources/jcef/PROJECTMAP.md) |
| `src/main/resources/META-INF/` | `plugin.xml`, plus `claude-terminal.xml` and `claude-git.xml` for the two optional dependencies, and the licences and third-party notices that ship inside the artifact. | — |
| `src/test/kotlin/` | Unit, source-scanning contract, headless-component and integration tests, plus the drift detector. All of it runs inside the one `test` task. | [map](src/test/kotlin/dev/lain/claudejb/PROJECTMAP.md) |
| `src/test/frontend/` | vitest + jsdom over the real inlined modules. devDependencies only; nothing here ships. | [map](src/test/frontend/PROJECTMAP.md) |
| `src/test/resources/` | JSONL fixtures replayed by the integration suite against `bin/fake-claude`. | — |
| `src/uiTest/` | RemoteRobot end-to-end against a real running IDE. A separate source set, deliberately outside `check`. | [map](src/uiTest/PROJECTMAP.md) |
| `docs/` | Policy and procedure for humans: release, branching, compatibility, CI, drift, telemetry, troubleshooting, FAQ, and the ADRs. **No backlog** — pending work is tracked outside the repository, deliberately. | [map](docs/PROJECTMAP.md) |
| `scripts/` | `gen-projectmap.py` (this map and its gate), `css-usage.py` (unreachable CSS rules), `split-css.py` (a one-shot migration record — see Minefields), `probe-binary.sh`, `apply-rulesets.sh`, `bootstrap-ci.sh`, `gen-ci-signing-key.sh`, and `drift-baseline.properties`, the file `checkDrift` actually reads. | — |
| `.github/` | The workflows — `ci`, `codeql`, `drift`, `release` — the versioned branch rulesets, the templates and Dependabot. | — |
| `config/detekt/` | detekt configuration and baseline. **The baseline is not a dumping ground** — see Minefields. | — |
| `bin/` | `fake-claude`, the deterministic stand-in the integration suite drives instead of the real binary. | — |

## Entry points

- **Plugin** → `src/main/resources/META-INF/plugin.xml` → tool window `Claude Code` →
  `ui/ClaudeToolWindowFactory.kt` → `ui/ChatTabsPanel.kt` → one `ui/JcefChatPanel.kt` per chat.
- **Session** → `session/ClaudeSession.start()` → `session/SessionLauncher.buildArgs` →
  `process/ClaudeProcess.kt` (stdio) → `protocol/ProtocolParser.kt` → `ClaudeSession.onEvent`.
- **Web app** → `ui/jcef/JcefHost.kt` assembles `shell.html` + the CSS block + the scripts in `appNames`
  order, pins a CSP hash over each, and delivers the page down the `PageRoute` ladder; the page boots
  `app-core.js` and registers `window.cc.*`.
- **Test stand-in for the binary** → `bin/fake-claude` with fixtures under `src/test/resources/`.

## Commands

Nothing here records what a command *answered* — run it. `JAVA_HOME` must point at the JBR: the toolchain is
JDK 21 and the IDE runs on JBR 21.

| What | Command |
|---|---|
| Everything, the way CI does | `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew check` |
| JVM tests (unit + contract + headless + integration) | `./gradlew test` |
| One test class | `./gradlew test --tests '*SensitiveGuardTest*'` |
| Static analysis | `./gradlew detekt spotlessCheck` |
| Coverage, verified / as HTML | `./gradlew koverVerify` · `./gradlew koverHtmlReport` |
| **This map agrees with the code** | `python3 scripts/gen-projectmap.py --check` |
| Regenerate this map | `python3 scripts/gen-projectmap.py` |
| Frontend tests | `OPENSSL_CONF=/dev/null npm test` |
| Frontend lint and formatting | `npm run lint` · `npm run format:check` |
| Dependency audit, in the scope that ships | `npm audit --omit=dev --audit-level=low` |
| Protocol drift against the installed binary | `./gradlew checkDrift -PclaudeBinary=/usr/bin/claude` |
| Marketplace compatibility across the IDE range | `./gradlew verifyPlugin` |
| …against locally extracted IDEs instead of downloads | `./gradlew verifyPlugin -PlocalIdePath=<dir>[,<dir>…]` |
| Unreachable CSS rules | `python3 scripts/css-usage.py` |
| Build the installable zip → `build/distributions/` | `./gradlew buildPlugin` |
| Run a sandbox IDE with the plugin | `./gradlew runIde` |
| End-to-end UI suite | `./gradlew runIdeForUiTests` then `./gradlew uiTest -PuiTest.enabled=true` |

`./gradlew spotlessApply` rewrites the whole project — never run it while anything else is editing the tree.
On this machine only: node needs `OPENSSL_CONF=/dev/null`, and `claude` is a system install at
`/usr/bin/claude` while `checkDrift` defaults to `~/.local/bin/claude`.

## Conventions and invariants (repository-wide)

- **The UI is JCEF. Swing is not an option.** Anything the user looks at is HTML/CSS/JS under
  `src/main/resources/jcef/`. The Swing chat UI was deleted in 4.0.0, and a tab strip was reimplemented in
  Swing twice before being thrown away both times.
- **The binary writes files, the IDE does not.** On approval the `claude` process performs the write; the
  plugin shows what is about to happen, then refreshes the VFS.
- **Credentials travel by environment, never argv**, and never reach logs, the transcript or XML.
- **The plugin is the source of truth for `permissionMode`.** `system/init` reports the launch-time mode on
  every turn, and adopting it is the "reset to default" bug.
- **Never mirror raw CLI output.** Every state is reconstructed from the event's structured fields.
- **Where new behaviour goes**: a `ClaudeSession` collaborator, a `ui/` collaborator, a JS module or a JSON
  builder — never back into `ClaudeSession` or `JcefChatPanel`, which are an orchestrator and an assembler.
  One file per seam, **registered in a table, never appended as a branch**.
- **One state vocabulary** for everything the page colours: `running` · `completed` · `failed` · `stopped`,
  decided host-side in `ui/jcef/JcefStatus.kt`. The page paints the word it is sent and derives nothing.
- **Every settings mutation goes through `ClaudeSettings.update {}`.** A bare `state.x = y` is a change that
  silently does not survive a restart.
- **Two parsers for one format is a bug waiting to happen** — the duplicated-thinking defect of 4.0.4 came
  from exactly that. `SessionTranscriptReader.parseEntries` is the only JSONL parser, and `AgentRegistry`
  reuses it deliberately.
- `protocol/` and `ui/jcef/JcefBridge.kt` stay free of IDE classes so they unit-test on a plain JVM.
- **Ship no deprecated or scheduled-for-removal platform API.** If `verifyPlugin` flags one, it is a blocker,
  not a warning.
- Threading: I/O and parsing off-EDT; every UI mutation on the EDT through the session's `edt {}` dispatcher.
- Tests live under the mirrored package path. Headless and integration tests must run inside the `test` task —
  the platform runtime is only wired there.
- Conventional Commits, enforced by `.githooks/commit-msg` and commitlint.
- **This map is distributed and half-generated, and the halves never mix.** Everything between
  `<!-- MAP:GENERATED BEGIN -->` and `<!-- MAP:GENERATED END -->` is overwritten by
  `scripts/gen-projectmap.py`; the prose around it is not, and `checkProjectMap` fails when the two disagree.
  So the generated half answers **where** and the hand-written half answers **why** and **careful** — a note
  written inside the markers is lost on the next run. **A symbol's one-line "Owns" is extracted from the
  source, never typed into the map**: the first sentence of its KDoc for Kotlin, and for a JS module the
  header's explicit `Owns:` line, which **outranks** the em-dash subject on the title line. Wording a row
  differently means editing the KDoc or the header, which is what keeps the map from drifting from the code.

## Minefields (repository-wide)

- **The signature defect of this repository is code that is implemented, tested and unreachable.** It has
  happened repeatedly, in features users were waiting for. `ReachabilityContractTest` is the gate that catches
  it now; when it fires the answer is to wire the symbol or delete it, **never to exempt it**. Three of its
  blind spots are covered elsewhere and the rest are not: `bridge-contract.test.js` (a `window.cc.<name>` the
  host calls and no module implements) and `bridge-inbound.test.js` (a message type the bridge parses and the
  page never sends · a `cc.<name>` neither side calls). **Nothing gates class MEMBERS, and nothing gates
  reachability along a PATH** — a collaborator that is called, but not from every route that should reach it,
  compiles, tests and ships. Closing a tab, restarting a session, restoring one and pinning a second view of
  it are four different routes, and each is a place a call site was left behind.
- **The plugin running in the IDE is not this working tree, so behaviour observed through the IDE is evidence
  about the INSTALLED build until `git diff HEAD` says otherwise.** There is no hot reload here: a build is
  produced, installed by hand and validated. A defect reproduced in the IDE — a refused permission, a stale
  label, a missing menu entry — may already be fixed in the tree, and diagnosing it by reading the tree's
  source attributes the behaviour to code that never ran. **Establish which build produced the observation
  before explaining it.**
- **`com.intellij.modules.jcef` is a HARD dependency and the floor is a BUILD, not a branch**
  (`253.29346.138`). A bare `sinceBuild = "253"` offers the plugin to an IDE that refuses to load it.
  `JcefDependencyContractTest` enforces this; `verifyPlugin` cannot, because it resolves against the whole
  distribution rather than against the plugin's classloader.
- **`PluginId.getId(…)` is banned** — it binds to `PluginId.Companion` and dies with `NoSuchFieldError` on
  IDEs below 252. Use `util/InstalledPlugins.kt`.
- **Property and `init` declaration order matters in any class body.** Kotlin runs initializers and `init`
  blocks in declaration order, so a property declared below an `init` that reaches it is null while the
  constructor runs — and the compiler only reports the *direct* reference, never one made through a function
  the `init` calls. `InitOrderContractTest` scans all of `src/main/kotlin` for the rest; it keys on a
  four-space indent, so a nested class's own `init` is outside it. The incident is documented in
  `ui/JcefChatPanel.kt`: it threw inside the constructor and no chat could be opened at all.
- `permission/ToolInputScanner.pathCandidates` walks **every string leaf** of a tool input, and every token of
  a command, as a path candidate — and `permission/ForeignTerritory` hard-denies a hit **regardless of caller
  trust**, with no override, telling the user which security switch to turn off to get their edit through.
  Every false positive this plugin has shipped was born in that pair, each found live and none by a test.
  Change either file with the local map and the rules' own KDoc in front of you
  ([map](src/main/kotlin/dev/lain/claudejb/permission/PROJECTMAP.md)).
- `ui/jcef/JcefHost.kt` — the CSP is hash-pinned over the exact bytes of each inline script **and** of the
  concatenated stylesheet. Editing an inline block in `shell.html` changes the hash; anything needing
  `unsafe-inline` is a no, and so is loading an asset by URL. **`appNames` and `CSS_PARTS` are ordered
  contracts**: there is no module system in the page, files meet through `window.cc`/`window.CC`, and a file
  omitted from `appNames` is silently not served.
- `src/main/resources/jcef/shell.html` — `#boot` and `#auth-card` are children of `#conversation`, inside the `#work`
  wrapper alongside `#dock` and `#palette`. That nesting is the point: hoisting either back up to `#app`
  re-covers the chat tabs and the composer while a chat starts.
- **KDoc must not contain a literal `/*`** — Kotlin block comments nest, and it produces "unclosed comment".
- **The plugin deletes exactly one file, ever** (the legacy credentials file, and only after the safe has
  accepted a copy). `NoFileDeletionContractTest` fails the build if a second deletion appears.
- **detekt's baseline is not a dumping ground.** Adding to it instead of fixing the finding is how a gate
  stops meaning anything.
- `session/WorkspaceDiffReview.kt` rebuilds the BASE side of the session diff by applying the binary's hunks
  **backwards** over the working tree, and refuses when a hunk does not describe what is on disk. Do not
  "improve" that into a best-effort reconstruction: a review tool showing a fabricated left-hand pane is worse
  than one showing none, because nothing on screen distinguishes them.
- `bin/fake-claude` and `src/test/resources/` — the integration tests' contract. A fixture edited without its
  test is a green suite that proves nothing.
- `scripts/split-css.py` — **one-shot and already applied.** Kept as the record of a mechanical migration;
  re-running it now would corrupt the tree.
- `.github/workflows/release.yml` — **merging to `main` publishes to the Marketplace unattended.** There is no
  approval gate; the environment scopes the credentials, it does not hold the release. `guard` asserts the
  commit is reachable from `main` before any secret is in scope. **Never tag a release by hand** — the
  workflow cuts the tag from `build.gradle.kts`, and published tags are immutable.
- **A branch ruleset references a check by its job's display name.** Renaming a CI job does not fail the
  gate — it silently stops applying.
- The whole 5.5.0 release is **uncommitted**. Treat any history operation as destructive.

## Out of the map

`build/`, `.gradle/`, `.kotlin/`, `.idea/`, and `node_modules/` — the Agent SDK there is **protocol reference
only** and ships to nobody. The vendored `marked.min.js`, `purify.min.js` and `highlight.min.js` under
`src/main/resources/jcef/` are third-party artefacts and are never edited by hand; their notices live in
`THIRD-PARTY-NOTICES.md` at the repository root, and the build copies them plus `LICENSE` and `LICENSES/`
into the artifact's `META-INF/` — a permissive licence's notice obligation binds on redistribution, and CI
asserts they are present in the zip.
