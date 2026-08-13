# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.5.0] — 2026-08-11

### Fixed
- **The plugin was dead on 2026.2.** From build 262 the platform ships the embedded browser as a separate
  bundled plugin (`com.intellij.modules.jcef`), and one that does not declare a dependency on it gets no
  browser classes at all: every chat died on `NoClassDefFoundError: com/intellij/ui/jcef/JBCefApp` at
  `JcefHost.<init>`. The whole UI is that browser, so there was nothing to degrade to. The dependency is now
  declared, hard — an optional one that cannot be satisfied is skipped, which would have left 262 exactly as
  broken and just as silent. That module id does not exist before 2025.3, so **the minimum IDE is now
  2025.3**; on 2025.1/2025.2, stay on 5.1.1. `verifyPlugin` had been reporting Compatible throughout and was
  right to — it resolves against the whole IDE distribution, not against the plugin's classloader, which is
  where the failure lives — so the gate is a source contract instead: touch `com.intellij.ui.jcef` and the
  descriptor must declare it.
- **Agents showed as failed while they were working.** `restoring` is set when a chat comes back from disk
  and is never cleared (it is what admits that chat's own subagents), so the rule "an agent nobody watched
  start belongs to a previous run" swallowed agents launched afterwards in that same chat. Restoring open
  chats is the default, so this was every agent in a freshly reopened IDE: red, while plainly running.
- **Every agent of every past session was painted red after a restart.** A settled status is per-process
  memory, so restoring left the plugin knowing nothing — and red does not merely look wrong, it asserts that
  they failed. The binary had already written the answer: an agent's transcript ending on
  `stop_reason: end_turn` finished, anything else was cut off.
- **A nested subagent never stopped running.** It has no `toolUseId` of its own, so nothing could ever settle
  it; it now follows its parent, which it cannot outlive.
- **Every agent was also being registered as a background task** — a row with no description whose "output"
  was the agent's own transcript, i.e. pages of raw JSONL where a command's output belongs. `task_notification`
  fires for agents too, and it was creating entries; only a `tool_result` carrying `backgroundTaskId` makes a
  task ours.
- **The tab row was neither scrollable nor reachable once a few chats were open.** Three faults, stacked. The
  row never bounded its width, so the strip *grew* instead of overflowing and the extra tabs were painted
  outside the tool window, where no amount of scrolling reaches them because there is nothing to scroll. The
  tabs had also become much wider, since a chat is now named after the prompt that started it, and the label
  had an ellipsis rule but no width to trigger it. And the scrollbar was hidden on the grounds that the wheel
  would do — which a vertical wheel does not, for a horizontal row, in Chromium. The row is bounded now, the
  label is capped with the full title in the tooltip, the wheel gesture is translated, the row itself is the
  handle so you can grab it and drag (with a threshold, so a click is still a click), and selecting a chat
  **centres** it — which is what makes ordinary use need no dragging at all.
- **The view buttons floated over the transcript** you were reading, and over the tabs. Overlapping a
  focusable tab is WCAG 2.2 SC 2.4.11 (Focus Not Obscured); they are items in the tab row now, so it cannot
  happen by construction rather than by keeping a padding in sync with the width of the words.
- **Hovering another chat's tab showed your own agents.** The bar is rebuilt several times a turn, and the
  reopen re-anchored to the first `⋮` with no chat id — i.e. the selected chat.
- **The waiting screens covered the chat and flashed on every new one.** Install, sign-in and loading were
  full-window overlays, so they hid whatever they were laid over — first the chat tabs, so you could not
  switch chats while one was starting, and then the composer. And because the binary often comes up in a
  fraction of a second, opening a chat painted a full-window panel and removed it again, which reads as the
  whole plugin flashing. They are **content of the transcript** now, not overlays: a row cannot cover what it
  does not own. The composer stays usable throughout — a prompt typed while the binary starts is queued and
  sent when it is ready — and the loading screen holds off for 0.35 s, so a fast start never draws it at all.
- **A restored chat showed the binary's own bookkeeping as things you had said.** `<task-notification>` lines
  and the "Caveat: the messages below were generated…" preamble ride on `user` records in the session file;
  they are now recognised for what they are, both in the transcript and in the title a chat is given.

### Changed
- **The chat is faster, and the reason is that it stopped doing work nobody asked for.** Three things, all of
  them repeated work removed rather than work made cheaper:
  - **The tab bar redrew itself on every push from the host** — several times a turn, on pushes that change
    nothing you can see, because an agent's transcript grew or a token landed. It now compares a signature of
    everything it is drawing (the row *and* the panel hanging off it) and an identical push is a no-op. The
    earlier attempt waived that skip whenever a menu was open, so resting the pointer on a tab meant every
    push rebuilt the whole row and then re-anchored and reopened the panel underneath the cursor, several
    times a second on a session with agents running: that is the flicker that was reported, and folding what
    the open panel draws into the same signature is what fixed it. Three regression tests assert the identity
    of the DOM nodes across an identical push, so a rebuild cannot come back unnoticed.
  - **The dashboard rebuilt itself while hidden**, on the reasoning that the DOM should be kept fresh — which
    meant laying out the Workloads diagram and measuring its SVG for a panel nobody was looking at, on every
    session push. Opening the panel renders anyway, so the work was pure waste; while hidden the payload is
    now simply stashed.
  - **Every agent gets a tab, but not a browser.** The per-agent transcripts are switched inside the one
    embedded browser the chat already had. A browser per agent would have been a Chromium process per agent,
    on the very session this feature exists for — the one running dozens.

  The waiting screen contributes the last piece: it no longer paints a full-window panel for a start that
  takes 100 ms (see above).
- **The code was cut down to match.** Measured against the commit this release branched from: no source file
  over 500 lines remains except `ClaudeSession` (2 779 → 2 507) and the browser host — **seven such files
  became two**. `protocol/Protocol.kt` (959 lines) was dissolved into eleven new files; `JcefChatPanel` went
  943 → 305 lines behind seven collaborators; `SensitiveGuard` 814 → 292 across six files; the settings page
  580 → 97 across seven sections; `SettingsStore`'s hand-written serialiser (85 lines, cyclomatic complexity
  36) became the generated one. The frontend went from five modules — the largest 1 875 lines — to thirty,
  none over 575, loaded in a declared order that is now a contract. **detekt's baseline was not touched**: it
  still carries the same two `ClaudeSession` entries and nothing else.
- The stylesheet is now seven files concatenated in cascade order by `JcefHost.CSS_PARTS` instead of one
  2 959-line file; the split was verified byte-identical before it landed, and the tests read that same list.
  Twenty-six rule blocks nothing could reach went with it, 159 lines in all: the Agents / Subagents /
  Background list views the Workloads diagram replaced, and selectors the markup no longer emits —
  `.plan-body`, `.q-body`, `.ro-bar`, `.ghost-text`/`.ghost-key`, `.spacer`, `#empty .star`.

### Added
- **A tab per agent, with its own transcript**, reachable from a bar under the chats that keeps the whole
  tree — agents, their agents, background tasks — one hover away. A finished agent keeps its tab; closing one
  hides a view and destroys nothing; any subtab can be pinned as a tab of its own.
- **Workloads**: everything running across every open chat as one diagram, replacing the three lists that
  were three views of the same tree.
- **Background tasks that outlive themselves** — the binary stops listing a task the moment it ends, which is
  exactly when its output is worth reading. The task, its command and its output are kept, tailed live from
  the file the binary writes, and rebuilt from the session transcript after a restart.
- **Settings moved into the IDE's password safe** (the OS keychain), one encrypted document shared by every
  project. They used to sit in `.idea/claude-code.xml`: per project, in the clear, and committable —
  including the env block, which is where an API key ends up. Existing settings are adopted on first run.
- **A chat is named after what you asked it**, instead of being "Chat 3" for its whole life. The binary can
  generate a title, but only for an interactive session — across thirty real sessions on a developer machine
  not one carried a generated title, because the plugin runs it in print mode. So the plugin falls back the
  way the binary itself does for display: the first thing you actually asked, one line, cut on a word. The
  order of authority is unchanged and this comes last — a rename you typed wins, then a generated title if
  the binary ever writes one. It applies in the same three places at once: the live tab, the tabs restored at
  startup, and the list behind "Open Previous Session…".
- **Git context in the tool window's ⚙ menu** — the checked-out branch is in the menu label itself, so the
  menu answers "which branch is Claude working on" without opening anything, plus recent commits (one line
  each: hash, subject, author, age, how many files) and the history of the current file. Both hand off to the
  IDE's **own** Git Log rather than drawing a second, worse one inside a chat panel — the same reasoning that
  keeps diffs on the platform's diff viewer. **It only ever reads**: nothing here moves a ref, rewrites
  history or talks to a remote. On an IDE without the Git plugin, or in a project that is not a working copy,
  the entries are simply absent rather than shown dead — and that is re-derived every time the menu opens, so
  running `git init` takes effect without reopening anything.
- **The agent is told what it is running inside.** Three things it cannot infer from the protocol are appended
  to its system prompt at launch: that the transcript is a native GUI and not a terminal, so terminal-shaped
  output is wrong by construction; that its file edits become a reviewable diff and its paths become clickable
  links, which changes what a good answer looks like; and that a deterministic guard can refuse a call, so a
  refusal reads as an answer instead of something to route around. It is fixed text — no machine name, no
  environment value, no project content, no credential — and it is **not** a security control: nothing in it
  softens a rule or describes how to get past one.

## [5.1.1] — 2026-08-10

### Fixed
- **The plan limits stopped refreshing whenever the panel was not on screen.** The poll was gated on
  `isShowing`, so a collapsed tool window or a chat tab that was not the selected one asked for nothing at
  all — and a quota window is not the plugin's state to begin with: other sessions, other devices and
  claude.ai spend the same windows, and a **reset is a wall-clock event that owes nothing to this IDE**. The
  figure on screen was therefore whatever the last probe happened to catch, and it only moved again when
  something else triggered one — a turn, or opening the dashboard. "It only updates when I talk to the agent"
  is precisely what a visibility-gated poll looks like from outside. The gate is gone and the period is 30 s;
  what it was saving is one control request per half minute against a process that is already running, and
  the event-driven refreshes (turn edges, `rate_limit_event`, dashboard open, session ready) are unchanged.

### Added
- **The chat's plan-limit row now says how long each window has left** — `Reset time: 4h 18m` on its own line
  directly under that window's bar, with the full sentence in the tooltip. A percentage alone does not say
  whether it is urgent: 90% with eight minutes to go and 90% with six hours to go are different situations,
  and only the dashboard was answering that. Under the bar rather than beside it because the row is already
  three items wide per window, and a fourth made the countdown the first thing to be squeezed out — the one
  case where it matters most. The countdown is computed by one function in `app-core`
  (`CC.resetIn`/`resetInShort`) that the dashboard card now shares, and a window with no reset time renders
  no element rather than an empty slot that would read as "resets now".

### Changed
- **Every `get_usage` poll now logs the reply it got**, `rate_limits` verbatim (truncated), at INFO. The
  derived per-window lines cannot answer the question that keeps coming up — *is the number on screen stale,
  or is the server still saying that?* — because a window the reply omits leaves no line at all, and one
  carried forward from the previous poll is indistinguishable from a fresh one. It immediately earned its
  place: a live capture showed **two of three consecutive polls** coming back in the header-seeded shape
  (`five_hour`/`seven_day` only, no `limits[]`, `resets_at` rounded to `.000Z`), which is the degraded reply
  5.1.0's merge exists for, and confirmed the binary does not cache the endpoint.
- `RateLimitInfo.resetsAtIso()` puts the epoch-seconds → ISO-8601 conversion on the model, so a window that
  reaches a surface from the *event* stream and one that arrives in the `get_usage` *report* are
  interchangeable to everything that renders them. It was a private copy in the dashboard's builder, and the
  composer needed the same thing.

## [5.1.0] — 2026-08-10

### Added
- **An "Other models" group in the model picker**, holding previous generations (Opus 4.8 → 4.0, Sonnet 4.6 →
  4.0, Sonnet 3.7 and 3.5, Haiku 3.5). Collapsed by default so the four current models keep the menu they had,
  and expanded automatically when the selected model lives inside it.

  The list is **curated in the plugin**, which deserves stating plainly because this repository removed a
  hardcoded model label in 4.3.3. There is no runtime source for it: the binary's selectable catalog — the
  `initialize` reply, and the identical answer to the `list_models` control request — contains only the current
  generation, and `ModelInfo` carries no `deprecated`/`legacy` flag. The binary still *accepts* these ids, it
  just will not list them. The distinction that makes a curated list defensible here: these are **historical**
  ids, which never change and never disappear, so the list can only gain entries. What went stale in 4.3.3 was
  a label describing the *current* tier. Nothing here names a current model, and a test enforces that.

  Choosing a model the account cannot run is handled rather than left to fail: `set_model` is now sent as a
  **correlated** control request, and a refusal restores the previous model and says so in the transcript
  instead of leaving the tab pointed at a model every later turn would fail on.

- **Per-model plan limits — Fable among them — are reported.** `get_usage` returns them in
  `rate_limits.model_scoped`, an *array* alongside the keyed windows rather than another key inside them
  (`sdk.d.ts`: `{ display_name, utilization, resets_at }[]`, and its own example names `'Fable'`).
  `parseUsageReport` walked only the keyed windows, so every per-model figure the server sent was dropped on
  the floor — which is why the CLI's `/usage` showed a Fable row the plugin never did.

  Reading that array is necessary and **not sufficient**, which is what the first attempt got wrong: the
  binary does not relay `model_scoped`, it *synthesises* it, and only behind its own remote config. Its
  projection (`IUt(limits, jJe())` in 2.1.223) reads the `tengu_usage_overage_included_models` gate, returns
  an empty list the moment that gate is empty, and the key is spliced into `rate_limits` only when the
  projection yielded something — so in a `--print` session it simply never arrived, which is why the plugin
  logged `five_hour` and `seven_day` and nothing else while the same account's interactive `/usage` listed
  Fable. The plugin therefore also walks the **raw `rate_limits.limits[]` array the projection reads from**,
  which does ride through untouched — the binary's own `/usage` formatter assumes as much, calling `IUt` on
  this very payload — taking the `weekly_scoped` entries that name a model, with the binary's filter and
  without its allowlist. Dropping the allowlist is deliberate: it selects which models get *overage billing*,
  not which limits a user is subject to, and a limit that meters you is worth showing whether or not you can
  pay past it. `resets_at` is epoch seconds there as often as a string, so it is normalised rather than
  deserialized — a numeric one would have failed to decode and dropped the whole window in silence.

  And a usage refresh is now **merged** into the last one instead of replacing it, because the same fetch has
  a second fallback that omits windows: `loadPlanRateLimits` gives `/api/oauth/usage` 5 s, and on a timeout, a
  429 or a fieldless body it substitutes `seedUtilization()` — an object rebuilt from the rate-limit *response
  headers*, which structurally carries only `five_hour` and `seven_day`. It is flagged `status:"seeded"` and
  then accepted identically to a full reply, so a poll that simply failed was indistinguishable from one
  saying the per-model window no longer exists — and the Fable bar blinked out and back every few polls.
  Merged by window key over the whole set, since `seven_day_opus`/`seven_day_sonnet` are missing from a seeded
  object for the same reason and would flicker the same way; a carried-forward window keeps the last figure
  actually reported for it and the next real refresh overwrites it. The extra-credit balance is deliberately
  not carried: `null` there already means "this plan has none" as often as "this reply did not say".

  They are keyed `model_scoped:<display_name>` because the quota-crossing record is kept per window and has to
  stay stable across refreshes, and titled from the server's own `display_name` — the *only* source for it,
  since nothing in the plugin can name a window the server invents. An entry whose name collides with a keyed
  window is dropped rather than duplicated, one missing a name or a figure is skipped, and they sort after the
  known windows so the row order the user already reads does not shuffle when Anthropic adds a model.

### Removed
- **The `nimbus_quill` usage window is no longer shown.** The claude.ai usage endpoint emits it and the CLI
  relays it untouched; it appears in no version of the binary and in no SDK type, so nothing here can say what
  it meters — it rendered as "Nimbus quill 0.0%", a row that asks a question and answers none. Hidden **by
  name**, deliberately not by a general "hide unknown windows" rule, which would silently swallow the next
  real limit; the moment it means something, deleting one line brings it back with its label, bar and ordering
  intact.

  It kept appearing anyway, because the filter sat on one of the **two** paths that feed a window to the UI:
  the `get_usage` report was filtered, the `rate_limit_event` stream was not, and that is the door it was
  arriving through. The rule is now applied on both (`isHiddenUsageWindow`), and on the event path the window
  is dropped whole rather than merely hidden — it must not become the session's `rateLimit` either, which
  drives the single-number quota bar.

### Fixed
- **A quota notification announcing 100% when almost nothing had been used.** `get_usage` reports each
  window as a percentage on a 0–100 scale — `sdk.d.ts` says so on every window, and a live reply from
  `claude` 2.1.222 carries `8` and `67`. `ClaudeSession` held a private copy of an "the wire sends both
  0–100 and 0–1, accept either" heuristic that multiplied any value `<= 1.0` by a hundred. So a window at a
  genuine **1%** was reported as **100%**, crossed the 85% threshold, and raised an IDE notification telling
  the user their plan was spent — at the moment they had spent almost none of it, which is to say right
  after a window resets. The heuristic is undecidable at exactly 1.0 by construction: it cannot tell a full
  window from a barely-touched one.

  The rule now lives once, on the model (`UsageWindow.utilizationPercent()`), with no scale guessing: the
  value is already a percentage. Two of the three copies had been removed in 5.0.1 when the dashboard
  stopped rounding; this was the third, and the only one wired to notifications, which is why the bars got
  quieter while the notifications kept shouting. The event path (`RateLimitInfo.utilization`, genuinely a
  0..1 fraction) is unchanged and was never affected.

### Changed
- **The plan limits are their own row under the status line**, one labelled bar per window, instead of dots
  at the end of the readout. Inline, they sat behind `Running… / Context 65% / 65.3k out / 0 reasoning` on a
  wrapping row — so the windows *nearest their cap*, the ones the row exists for, were the ones most likely to
  wrap out of sight in a narrow tool window. The row is a `repeat(auto-fit, minmax(150px, 1fr))` grid: it
  spends the full width at any size and drops to fewer columns as the panel narrows, with no media query and
  no fixed layout to outgrow. The bar is clamped to 100%; the number is not, because a window reported past
  its cap is exactly the figure worth reading.

- Quota notifications title themselves through `UsageWindow.title(key)` rather than from the key, so a
  per-model window announces "Fable quota at 85%" instead of the synthetic `model_scoped:Fable`. The record
  that decides whether a threshold has already been announced stays keyed by the key, which is what makes it
  survive a refresh.

- The `get_usage` path now logs each window's raw utilization and the percentage derived from it, at INFO.
  When the false 100% was reported there was nothing in `idea.log` to check it against, because only the
  *event* path carried a trace — and that one is `debug`, so it is off by default. A number the user can see
  should leave behind the value that produced it.

## [5.0.1] — 2026-08-10

### Fixed
- **The subscription login did not survive a restart.** The credential was stored correctly — in the IDE's
  PasswordSafe, which resolves to the OS store — KWallet or GNOME Keyring through the Secret Service on
  Linux, the Keychain on macOS, the Credential Manager on Windows — and it was still there after the reboot,
  confirmed by reading the entry back out of the OS store directly. What expired was the *access
  token* inside it: the OAuth flow issues one good for hours (~10 h, measured), so any restart the next day
  found a perfectly persisted credential that no longer authenticated anything. `hasUsableToken()` answered
  false, and false meant "signed out", so the sign-in card came back every morning.

  The blob beside it always carried a **refresh token valid for weeks** and the plugin never spent it, by
  design: only the binary can, and it does so by rewriting `~/.claude/.credentials.json` — the exact file the
  vault exists to remove. The way out is that the binary has a **non-interactive** login for precisely this:
  given `CLAUDE_CODE_OAUTH_REFRESH_TOKEN` and `CLAUDE_CODE_OAUTH_SCOPES`, `claude auth login` takes a
  dedicated branch, mints a fresh credential and exits — no browser, no TTY, no user. So renewal is now the
  binary's job, exactly as it always was, and the plugin's job stays what it was: take custody of the result
  and delete the plaintext copy. No OAuth client here, no token endpoint called from the IDE, no file written
  back — the invariant `NoFileDeletionContractTest` and the vault's KDoc both state is untouched.

  Reported on **Linux and Windows**, and it is one bug rather than two: the binary's default credential store
  is its `plaintext` provider (`~/.claude/.credentials.json`) on every platform, so the vault takes custody
  the same way everywhere and the token expires the same way everywhere. The fix carries no platform-specific
  code — the only Windows-specific care is that the renewal environment strips `CLAUDE_CODE_OAUTH_TOKEN`
  case-insensitively, since environment names are case-insensitive there.

  **Scope: the subscription (OAuth) credential only.** An Anthropic API key is a different identity in a
  different slot — `providerApiKey:anthropic` in the same PasswordSafe, not `CLAUDE_CREDENTIALS_JSON` — and it
  has no expiry and no refresh token, so there was nothing to lose across a restart and there is nothing to
  renew now. `CredentialsVault.renew()` reads the `claudeAiOauth` blob and nothing else, and `envOverlay`
  withdraws entirely when an API key is present, so an API-key session is untouched by any of this.

  An expired-but-renewable credential now counts as an identity (`CredentialsVault.canRenew`), the renewal
  runs off the EDT at launch (`ClaudeSession.renewVaultedCredential`, before the launch env is built, and
  never while a sign-in is in flight), the refresh token rotates at every renewal so ordinary use extends it
  indefinitely, and a failed renewal arms a five-minute cooldown so the three-second boot watcher cannot turn
  a flaky network into a process spawn per poll. Sign-in is now needed only after a genuinely idle period, or
  when Anthropic invalidates the grant.

## [5.0.0] — 2026-08-05

The standards-compliance major. The repository was taken through the standards catalogue domain by domain —
application security, licensing, accessibility, supply chain, release engineering, testing and static
analysis — and the major number reflects that the **code** changed to comply, not only the documentation:
**108 files, +9 699 / −3 431 lines**, of which roughly 4 100 are the JCEF front end.

Compliance here is mechanised rather than asserted. Every claim this release makes is enforced by something
that fails a build: detekt and ktlint on Kotlin, ESLint and Prettier on the shipped JavaScript, per-package
coverage floors, a distributed-scope dependency audit, CodeQL on both languages, the plugin verifier across
the whole supported range with deprecated-API usage as a failure level, and artifact assertions that check
the published zip contains no npm code and does carry its third-party notices.

It did not stay purely that, and saying so is cheaper than letting a reader discover it: the release also
carries the **plan-limits panel** and a run of user-facing fixes (below). Nothing is removed or behaves
differently on purpose — but a release note claiming "no user-facing change" while shipping a new dashboard
card would be the kind of small untruth that makes the rest of the document unusable as evidence.

### Security
- **The protocol SDK was declared as a runtime dependency while never being one.** `@anthropic-ai/claude-agent-sdk` sat in `dependencies` although it is protocol reference material — kept so the Kotlin layer can be diffed against the binary's real surface — and is not executed or packaged. The published artifact contains jars and inlined web assets and **zero** `node_modules` entries, which anyone can confirm with `unzip -l build/distributions/*.zip | grep -c node_modules`. The consequence of the wrong declaration was seven permanent `npm audit` findings (three high) against code no user ever receives: an alarm backlog that cannot be acted on, which is worse than no alarm because it trains you to ignore the one that matters. Moved to `devDependencies`, so `npm audit --omit=dev` — the distributed scope — now reports **zero**. `SECURITY.md` states the triage boundary explicitly, with the command to verify it rather than a request to trust it.
- **Written threat model** ([ADR 0002](docs/adr/0002-threat-model.md)). `SensitiveGuard` was strong and undocumented: nothing said what it defends *against*, which makes coverage unarguable and restarts every bypass discussion from first principles. The ADR states the trust model (the user trusted; the `claude` binary trusted as software but untrusted as a *channel*; everything it relays — model output, tool inputs, MCP traffic, file contents, fetched pages — untrusted) and runs STRIDE over the three real surfaces. On indirect prompt injection it records the position deliberately: detection is not attempted, because content-level detection is unsolved and a control built on it would be a liability. Injection is **assumed to succeed**, and the defence sits where success does not pay — the guard judges the tool call and never the reasoning behind it, so a perfectly-injected model still has to ask to read the key, and still gets the same answer. Non-goals are listed as explicitly as goals.
- **The ignore rules had no protection for key material.** `.gitignore` covered build output and nothing else, so the working tree was one wrong answer away from a committed private key: `scripts/bootstrap-ci.sh` asks where to save a generated JetBrains signing key, and answering `.` drops `private.pem` into the repository. It now leads with a secrets section — `*.pem`, `*.key`, `*.p12`, `*.jks`, `chain.crt`, `passphrase`, `private.asc`, `*.token`, `.npmrc`, `.netrc` — with a single negation for `docs/ci-signing-key.asc`, the one key file that *must* be committed since without the public half nobody can verify a release. Verified by creating each of those files and confirming `git check-ignore` blocks it while the public key stays committable. Secrets come first in the file for a reason: a build artifact committed by accident is noise, whereas a private key committed by accident is **burned** — forks, clones, forge caches and CI logs mean rewriting history does not un-leak it, and the key has to be rotated regardless. The file itself remains **untracked by design** (it ignores itself): a published `.gitignore` is a public inventory of a maintainer's local directories and tooling, which is reconnaissance for no benefit to anyone installing the plugin.
- `SECURITY.md`'s supported-versions table still said `2.x`.

### Added
- **CI/CD on GitHub Actions, with publication gated three independent ways.** The repository had no working pipeline at all: the workflows had been deleted, and a comment in `.gitlab-ci.yml` had been asserting for months that GitHub Actions was "capped (billing)". That was **false** — the repository is public, and Actions on standard hosted runners is free and unmetered for public repositories; the account's Actions permissions were verified enabled. A false constraint written into a config file gets believed for years, which is precisely what happened. `ci.yml` now runs the full gate on `develop`, `main` and every `feature/**`, `bugfix/**` and `hotfix/**` branch — not only on the PR, because a bar you meet only at PR time is a bar you discover late. `codeql.yml` adds SAST over Kotlin and JavaScript. `release.yml` publishes to the Marketplace only when three things hold at once: a `vX.Y.Z` tag; the tagged commit **reachable from `main`**, asserted before any credential is in scope; and a human approval on the `marketplace` environment, where the four credentials are scoped and exist for no other job. The middle gate is the load-bearing one — without it, anyone who can push a tag can publish from any code, and the review the approval assumes becomes optional. `drift.yml` runs `checkDrift` weekly and **files an issue** rather than committing: whether a new protocol message should be modelled or ignored is a judgement call, and a bot that answers it would bless a gap silently. Every action is pinned by full commit SHA (a tag is mutable, and the action runs with this repository's token), with Dependabot proposing the bumps so the pinning stays free. Build provenance is attested and deliberately not overtrusted — a compromised runner can sign a build that genuinely happened on it.
- **Release artifacts are signed in the pipeline, by a key that is deliberately not the maintainer's.** The maintainer key is hardware-backed and non-exportable — which is what makes it worth trusting, and also why it cannot sign inside a runner. Automating the `.asc` therefore needs a software key in a secret, and that weakening is bounded rather than waved through: the secret is scoped to the approval-gated `marketplace` environment (no job reachable from a bare tag push can see it), the key **expires after a year** so an unnoticed leak stops mattering on its own, and its user ID says out loud that it is a CI key. That last point is the actual mitigation — if the two signatures were indistinguishable, a leaked CI key would impersonate a person. The two claims are now documented as distinct: the tag signature says *a person authorised this release*, the artifact signature says *this workflow produced these bytes*, and `SECURITY.md` tells users to check both. Generated by `scripts/gen-ci-signing-key.sh`, which works in a throwaway keyring and never touches the maintainer's. **The CI key is certified by the hardware key**, which is what makes the arrangement defensible rather than merely documented: without it a user is asked to trust a fingerprint printed in a file inside the very repository an attacker who could swap the key would control — a tautology, not a trust anchor. With it the chain terminates in hardware, and there is a revocation lever nobody holding the leaked key can undo. `scripts/bootstrap-ci.sh` performs the whole one-time setup, and `docs/CI_SETUP.md` documents each step for when it has to be done by hand.
- **Branch protection as versioned code** (`.github/rulesets/*.json`, applied by `scripts/apply-rulesets.sh`). Both `main` and `develop` require a pull request, an up-to-date branch, signed commits, and every CI check. Required approvals are **zero**, which reads like a hole and is the opposite: GitHub does not let an author approve their own pull request, so on a single-maintainer repository "require 1 approval" with no bypass actors means nothing can *ever* be merged — not by push, not by PR, not by admin. We established that empirically, by locking the repository and having to unlock it. The gate that remains is the mechanical one, which is also the one that cannot be talked out of. Raise it to 1 when a second maintainer exists; the rulesets carry that instruction inline. **No bypass actors, including admins** — the previous documentation preserved an admin bypass for a structural blocker that never existed, and a bypass is by construction used at the worst possible moment on the least-reviewed change. `.gitlab-ci.yml` is removed rather than retained: two pipelines that can each publish is one publisher too many.
- **Accessibility conformance work** (WCAG 2.2 AA; the EU Accessibility Act has applied since 28 June 2025). A `role="status" aria-live="polite"` region declared in the **static** `shell.html` — created lazily it would never announce its first message, which is the classic way to ship a silent live region — plus `CC.announce` with duplicate suppression, so a screen-reader user is told when a turn starts, finishes, or is blocked on a permission card. The transcript streams without ever moving focus, so without this the turn simply stalls in silence. Also a `:focus-visible` baseline covering every element whose outline the stylesheet suppresses (the find bar's input had no replacement at all), honoured under `forced-colors` rather than overridden. Ten frontend tests pin the structural guarantees; they do not certify conformance, which still requires a keyboard and screen-reader pass by a person.
- **Third-party attribution ships inside the artifact** — `THIRD-PARTY-NOTICES.md`, `LICENSE` and `LICENSES/*` are packaged under `META-INF/`. The plugin redistributes `marked`, `DOMPurify` and `highlight.js`, and a permissive licence's notice obligation binds on **redistribution**: a notices file that exists only in the repository does not discharge it for someone who installs the zip. DOMPurify is dual `Apache-2.0 OR MPL-2.0`, so the choice is recorded rather than left implicit.
- **[`AGENTS.md`](AGENTS.md)** — the operational runbook for agentic development (commands, gates, boundaries), complementing `CLAUDE.md`, which stays the architecture.
- **[`docs/adr/`](docs/adr/README.md)** — three decision records: [0001](docs/adr/0001-release-process.md) release process, [0002](docs/adr/0002-threat-model.md) threat model, [0003](docs/adr/0003-i18n-deferred.md) i18n deferred with the triggers that reopen it.
- **Conventional Commits enforcement** via `commitlint` and a **versioned** `.githooks/commit-msg` (enable with `git config core.hooksPath .githooks`). The hook self-tests and degrades to advisory if its own toolchain fails, specifically so it can never become a reason to reach for `--no-verify`.

### Changed
- **Published tags are now immutable**, recorded in ADR 0001 §3 as a correction of a real violation: `v4.3.2` and `v4.4.1` were each force-re-cut three times after being pushed. A tag is the identity of a shipped artifact; moving one means two people can hold different trees, different zips and different checksums while both believe they have the same version — which defeats the single thing a signature is for. A mistake found after tagging is now fixed by the next patch version. The already-moved tags are left alone, because re-cutting them to "fix" history would repeat the exact mistake.
- **`LoginCoordinator` extracted from `ClaudeSession`** (1965 → 1826 lines). The OAuth sign-in is a subsystem in its own right — the TTY-less `--print` session cannot host an interactive login, so it happens outside the session entirely through three ordered paths — and it now owns its own state. Mechanical, no behaviour change, full suite green across it. The two further extractions that were considered (`SessionRestorer`, `RewindCoordinator`) were **deliberately not made**: `restore` is 23 lines that touch six pieces of session state, and rewind is one of six identically-shaped control-request delegates. Both would have bought indirection rather than cohesion, and saying so is the point of recording it.
- `package.json` declared `"license": "ISC"` on a GPL-3.0-only repository and lacked `"private": true` — i.e. it was publishable to npm under the wrong licence. Corrected.
- **No contact email is published anywhere in the project.** The `<vendor email>` attribute is optional and has been dropped from `plugin.xml`; vulnerability reports now go through **GitHub private security advisories** rather than an inbox. That is the better channel on its own merits and not only a privacy measure: the report lands in a private thread attached to the repository, the discussion and fix stay linked to it, and a CVE can be requested from the same advisory — whereas an address in a public file is scraped far more often than it is used by a reporter.
- Protocol baseline re-verified and advanced to `claude` **2.1.222** / SDK **0.3.222**; `./gradlew checkDrift` green, protocol surface unchanged.
- The pull-request template now asks for **risk and rollback** — and for a published plugin, reverting a commit is not a rollback: a user on the bad version stays there until they update.

### Internal
- The frontend test harness (`src/test/frontend/helpers/load.js`) now extracts the shell DOM from the real `shell.html` instead of a hand-copied approximation. The copy had already drifted — it lacked `#a11y-status` — which is the worst failure mode a harness has: it does not fail loudly, it quietly tests something that is not the product.
- Frontend suite: 44 → **54** tests. JVM suite 677 → **682**.

### Static analysis, formatting and coverage — installed, then acted on
- **detekt and Spotless/ktlint added, and the 203 findings they raised were fixed rather than frozen.** Until now the entire quality bar for 13k lines of Kotlin rested on review, which is precisely what the standards say to mechanise. The first run produced 492 findings; tuning the rules with the reasoning written *at each setting* brought it to 203, and those were then worked down to **2**. `config/detekt/baseline.xml` holds exactly those two, both about `ClaudeSession`, both explained inside the file — it is a record of a decision, not a drawer. The distinction matters: a 203-entry baseline is a promise to nobody, a 2-entry one is a claim somebody has to defend in review.
- **The dispatch tables were split in two levels, keeping compile-time exhaustiveness.** `ClaudeSession.onEvent` was a single `when` over 47 event types — **244 lines, cyclomatic complexity 111** — the one function where every protocol concern in the plugin met. `ClaudeEvent` now declares seven sealed sub-interfaces (`Stream`, `Conversation`, `Control`, `Task`, `Notice`, `SessionSignal`, `HookTelemetry`) and dispatch picks the group, then the variant. The grouping is expressed in the **type** on purpose: a sealed hierarchy keeps the compiler checking exhaustiveness at *both* levels, so a new protocol event that nobody handles is a compile error rather than a silently dropped frame — which is the property `checkDrift` exists to protect, and was not up for trade against a complexity threshold. The groups are semantic, not cosmetic: they differ in what the host *owes* the binary (a `Control` frame must be answered or the binary hangs; a `Notice` is fire-and-forget). `JcefBridge.Msg` and `JcefChatPanel.onBridgeMessage` (complexity 46) got the same treatment, with the message groups mirroring the bridge's parsers one-for-one.
- **Several `when` chains were dictionaries written as control flow**, and are now data: `ProtocolParser.parseSystem` had 25 arms of which 21 were the same expression with two names substituted (complexity 29 → a `Map`), likewise the top-level frame decoder, and `EditorContextProvider.langForExtension` (26 arms → a lookup table). Adding a protocol subtype is now one line, and the shared fallback wiring is written once instead of 21 times where a mistyped argument would have been invisible.
- **Coverage is gated per package** (`koverVerify`), because risk here is not evenly spread: `permission/` decides whether the agent may read your SSH key, `ui/` paints a browser. Thresholds sit slightly *below* what each package measures, so they catch regression instead of inviting test-padding. `ui/`, `context/`, `process/`, `actions/` and `util/` are **excluded with the reason stated** rather than gated at a token value — gating them at 20% would dress the same fact up as a passing check. Policy, measured numbers and the known gaps are in `docs/RELEASE_CHECKLIST.md` §Coverage policy.
- **A "≥90% coverage target" was cited in the build for a requirement that did not exist.** `build.gradle.kts` claimed the figure was "documented in `docs/RELEASE_CHECKLIST.md`"; that file had never mentioned coverage, and the real number was **53.3%**. A number nobody measured, pointing at a rule nobody wrote.
- **ESLint and Prettier now cover the shipped JCEF frontend** — ~3.6k lines of JavaScript that ride *inside* the plugin jar and had never passed through any tool. `no-eval`, `no-implied-eval` and `no-new-func` are errors because the page runs under a hash-pinned CSP with no `'unsafe-eval'`: without the gate, code Chromium will silently refuse in a user's IDE can still reach `main`. Vendored `marked`/`DOMPurify`/`highlight.js` are excluded — a finding in them is not ours to fix, and fixing it would fork a dependency.
- **A `Static analysis` job** (`detekt`, `spotlessCheck`, `koverVerify`, `npm run lint`, `npm run format:check`) is now a **required check** on both protected branches. Everything above is only worth having if breaking it fails a merge.
- Two rules that both tools enforced were given a **single owner each**: `max-line-length` and `function-naming` are detekt's, because only detekt can scope an exception to the test tree. Running both meant the stricter-but-blinder tool decided, which is how you end up reformatting single-line NDJSON protocol fixtures to satisfy a tool that cannot be told they are fixtures.

### Fixed — defects the tooling surfaced
- **Token counts and CSS alpha values were formatted with the machine's locale.** `TokenFormat.trimDecimal` used the default-locale `"%.1f"`, so on a comma-decimal machine (Spanish, German, French…) a count rendered as `1,2k` inside otherwise-English UI — and worse, the trailing-`.0` test stopped matching, so a flat 1000 tokens displayed as `1,0k` instead of `1k`. The same bug in `JcefTheme.rgba` was not cosmetic at all: it emitted `rgba(217, 119, 87, 0,140)` — four components instead of three — so the browser **discarded the declaration** and the `--accent-soft`/`--link-soft` washes (text selection, the code-block Copy hover, the "View diff" hover, blockquote backgrounds) never rendered on those machines. Also fixed in the context-usage percentage and the colour-to-hex helper. All now pin `Locale.ROOT`.
- **Diff tabs were being persisted into the workspace and could never be restored.** Our diffs are in-memory previews (`ChainDiffVirtualFile` over a `mock:///` URL); the platform persists every open editor tab by URL without filtering by file system, so on the next start each one resolved to nothing. One workspace here had accumulated **13** such entries — all named `Claude · SKILL.md`, since the tab title is the file name and a skills repository has one `SKILL.md` per directory — producing 26 `WARN EditorsSplitters - No file exists` lines on every single launch. `DiffTabCleanup` now closes them on `projectClosingBeforeSave`, the one hook that runs *before* the state is written (`projectClosing` would be one step too late), and a wiring test pins the `plugin.xml` registration against the shipped descriptor — the failure mode being silence, not a stack trace.
- **`CloseAllDiffsAction` moved to a background update thread.** It reads one `CopyOnWriteArraySet`'s size; keeping it on the EDT put it in the queue behind everything the IDE does at startup. `InterruptAction` deliberately **stays** on the EDT and now says so in the code: it reads `ContentManagerImpl.mySelection`, an `ArrayList` mutated on the EDT with no synchronisation and no threading assertion, so moving it would trade a cosmetic log line for a rare `IndexOutOfBoundsException`.
- **Four defects in the shipped frontend**, all found by its first lint run: `obj.hasOwnProperty(k)` in both DOM-building helpers (breaks if the object carries its own `hasOwnProperty` — and those helpers build DOM from host-supplied data), an empty `catch` in the Vibe Mode theme restore that silently left the theme half-reverted, and two dead functions (`isAgentTool`, `esc`) nobody called.
- **`sniffMediaType` no longer confuses any RIFF container for WEBP.** Rewritten around named signatures (complexity 23 → 4), it now checks the four-byte *form type* that actually identifies the format, not just the `RIFF` header that WAV and AVI share.

### Fixed — a tab-killing regression, and the silences it hid

- **No chat could be opened or restored (regression, introduced on this branch).** `JcefChatPanel.pendingUntilReady` was declared *below* the `init` block that uses it. Kotlin runs property initializers and `init` blocks in declaration order, so the list was still `null` while `init` ran and the constructor threw `NullPointerException` — taking the whole tab with it, on new chats and on startup restore alike. `lastUsage`/`lastUsageAt` had the identical defect and stayed **silent**, because a nullable reference and a primitive read as `null`/`0` instead of throwing: the loud version of this bug was the lucky one. The compiler does not catch it — it flags a direct reference in an initializer, but here the read happens inside a function called *from* `init`, which it cannot see through — so `InitOrderContractTest` scans the sources and fails the build on any class-body property declared after its own `init`.
- **Nothing said the agent was still starting.** The binary is now launched *before* the tab is built (`start()` only dispatches, so `claude` boots while JCEF creates its browser), and a **boot screen** holds the tab until the process is up. Three states, not two: `running`, `starting`, and **neither** — that last one is a launch that failed (missing binary, declined trust prompt, refused remote-mount project) and it must bring the screen down, or the tab stays covered forever with no way to reach the notification explaining why. The screen is declared visible in the static shell, since at page load the process genuinely is not up yet.
- **Context and cost were a minute late, twice over.** A `javax.swing.Timer`'s initial delay equals its interval, so the first poll came a full `QUOTA_POLL_MS` after the panel attached — and that tick landed while the binary was still launching and returned early on the not-running guard, costing a second interval. Process-ready, tab-open and both turn edges now poll directly, and the timer **retires at the end of a turn**: context and cost cannot move while a session sits idle, so polling forever was a round-trip through the binary, per tab, for two numbers that provably had not changed.
- **The plan-limit figures disagreed with themselves.** A `get_usage` reply refreshed the dashboard bars but not the composer's dots, so the same number appeared immediately in one surface and "a while later" in the other, whenever some unrelated state change happened to re-push. Both are pushed together now. Opening the dashboard also refreshes them, which `requestUsage`'s own contract had claimed and the code had never done.
- **Reasoning tokens, context and output are rendered at `0` instead of omitted.** An item that only appears once it is non-zero is indistinguishable from one that failed to load — which is exactly how a fresh tab read: a lone "Idle" and no figures. Cost stays gated, because a currency amount of zero is noise rather than an ambiguity to resolve.
- **The CLI's `<tool_use_error>` wrapper reached the transcript verbatim.** `claude` 2.1.222 wraps a failed tool result's `content` in that tag pair and carries the same message *unwrapped* in a sibling field — framing for the model, not text for a human. Rendered as-is it put raw markup in a native GUI, the "never mirror raw CLI output" antipattern this plugin exists to avoid. Stripped only when it encloses the whole payload, so output that legitimately mentions the tag survives; `is_error` already conveys the failure structurally, and is what reddens the card.
- **A failed tool card hid its own error.** Tool output lives behind the card's collapse, so for a failure the entire message was "the header is red", and the text scrolled sideways rather than wrapping — hiding the actionable half at the end of the line. A failed card now opens itself **once** (tracked on the node, so it never fights a user who deliberately collapsed it) and its error text wraps. Healthy output still scrolls: wrapping code or a log corrupts its alignment.
- **`ToolSearch` was missing from the `SensitiveGuard` trust allowlist**, along with `AskUserQuestion`, `Mcp` and `FileRead`/`FileEdit`/`FileWrite`. `ToolSearch` is the one that mattered: it loads the schema of every *deferred* tool, so on a session that defers them, the call that unlocks all the others was the one landing in the third-party branch. Entries are only ever **added** to that list — it is a trust allowlist, not an inventory, and a missing first-party name is precisely the 4.4.0 hard-DENY incident. Found by diffing it against a live session's real tool inventory rather than against the SDK's type names, which are *not* the runtime registry (the SDK calls them `FileRead`/`FileEdit`/`FileWrite`; the tools are `Read`/`Edit`/`Write`).
- **A Markdown link whose href is a path did nothing when clicked.** The host handled `https://` and `jb://open` and dropped everything else without a sound — so `[BACKLOG](docs/BACKLOG.md)` was inert while bare paths written in prose worked, making the more deliberate link the one that failed. Both routes now go through a single authorising gate (`LinkResolver.isOpenable`). The scheme test requires **two or more** characters before the colon, so a Windows drive (`C:\src\main.kt`) stays a path rather than being mistaken for a URI scheme.
- **Copy on a message copied and said nothing.** Message-level buttons carry their own click handler and never reached the delegated code-block path that flashes "Copied", which reads as a broken button — and was reported as one. The flash helper is now exported and shared rather than reimplemented, so wording and duration cannot drift. The `.copied` class had been applied by the JS since 4.0.4 and **had no CSS rule at all**; it now has one.

### Interface

- **Onboarding: the plugin now installs and signs in Claude Code from inside the IDE.** A tab opened without the `claude` binary shows an install card instead of a loading screen that faded into an empty tab: one button per **official** install route for the current OS (Linux: install script, plus apt/dnf/apk when the distro is recognised; macOS: script and Homebrew; Windows: PowerShell, winget and cmd), each with the exact command it runs shown beside it, copyable — on a network that blocks one route, the command itself is the fallback. Commands run visibly in the IDE terminal; a manual path entry accepts a file or an install directory and is validated by running `--version` and requiring the answer to name Claude Code. Once the binary appears — by any route — the session starts on its own.
- **Sign-in lives in a card, not a command — and the credential does not live on your disk.** Signed out, the card is the first thing a tab shows, before a turn can fail on it. The subscription flow is fully native and requests the **full OAuth consent** — the reduced `setup-token` grant drops scopes Claude Code exercises, file upload among them, which is what a pasted attachment travels on: the binary's browser flow runs under a hidden PTY, the card shows the URL (copyable) and completes on its own when the browser finishes, so pasting the code is an optional fallback on the same screen rather than a step of its own.
  That login normally leaves its credentials in `~/.claude/.credentials.json` — plaintext on Linux, readable by every process running as you, and shared with the terminal CLI. The plugin does not leave them there: they are moved into the **IDE's password safe** (OS keychain / KWallet / DPAPI) and the file is overwritten and deleted, a login made in your own terminal included. **Nothing ever writes that file back.** The credential reaches the binary through the process environment instead, which is narrower (`/proc/<pid>/environ` is owner-only where the file was readable by anything running as you) and leaves nothing behind when the process exits. An orphan left by a hard IDE kill is folded back into the safe at the next launch.

  The binary keeps running against your own `~/.claude`, untouched, and it is handed the **whole** credential through the environment — access token, refresh token, **OAuth scopes**, subscription type, rate-limit tier and the account — not just the token. The scopes are the load-bearing part: the plan-limit windows come from an endpoint the binary only calls when the credential grants `user:profile`, so handing over a bare token left every session meter dark. That was misread during development as "the binary only reports this from its own configuration directory", and the fix attempted from that premise — a private configuration directory with your real configuration symlinked into it — **deleted the contents of the directories it linked to** when the session ended, session history included. That directory is gone, along with the recursive delete at its heart; the plugin now deletes exactly one file, ever: the plaintext credential it moves into the safe. A source-level contract test fails the build if any other deletion appears.

  An API key entered in the card goes to the same per-provider slot Settings uses, so the card and Settings ▸ Provider are one credential rather than two that disagree — and no provider's key can overwrite another's. A **valid key that the binary rejected** is fixed too: it requires each key to be approved once, and a `--print` session has nobody to ask, so the approval is recorded when you enter the key, and the key is verified before being stored at all. `claude auth status` validates whichever identity is effective and enriches the dashboard's account card, whose row always shows **Sign in** or **Log out** — and Log out stops the session first, then clears the IDE's copies, without touching your own terminal login. `/login` is no longer advertised in the palette — typed, it still works.

  Sign-in comes **before** the loading screen: verifying credentials needs no session, so an unauthenticated tab shows the card rather than launching a process to discover what it already knew. And all of it is re-checked continuously — installing the binary or signing in from elsewhere takes effect within seconds, with no tab to close and reopen.

- **Plan-limit bars** in the session dashboard and a matching dot in the composer readout: every rate-limit window plus the extra-credit balance, colour-graded by severity, animating to their value so the number and the bar settle together. Each source is read on the scale it actually uses — the live events carry a `0..1` fraction, the on-demand usage reply `0..100`.

- **The chat is reachable only while Claude Code is running.** Install → sign in → loading → chat, and any step backwards — the binary uninstalled, the credential gone, the process exited — stops the session and returns to the matching screen. The loading screen waits for the binary to answer rather than merely to start, so the first frame is drawn with the command list, model catalogue and account already in hand.
- **A unified entrance for every transcript row.** Messages, tool cards, thinking folds, recalled-memory folds, elicitation cards and notices all rise into place on the same curve; a completed tool call resolves with a single 1.5% beat, sized to register at the edge of vision rather than to be watched.
- **A boot overlay** covering the interval between launching the binary and the session being ready, with a distinct state for a launch that failed.
- **Reduced motion is driven by the IDE**, not by the browser's own media query, so it follows the setting the user actually changed. Its handling is explicit rather than a blanket freeze: looping indicators keep a legible resting state instead of stopping on their first frame.
- Failed tool output wraps instead of scrolling sideways; Copy affordances share one confirmation state; the loading indicator and empty state use the same Claude glyph, drawn as a character rather than an asset so the hash-pinned CSP is unaffected.

### Release integrity

- **Release tags are cryptographically verifiable.** The CI signing key carries an email identity, is registered on the publishing account, and is certified by the maintainer's hardware key; the workflow derives the tagger address from the key it signs with, so key rotation is self-contained and the two cannot fall out of step. The address is never written into a committed file. A key without an email identity now aborts the release.
- **The tag precedes the artifact.** `publish` cuts and signs the tag, checks it out, and builds from that ref, so the published bytes correspond to the ref that names them. Re-running the job on an existing tag is idempotent and replaces the assets in place, which makes recovery from a failed publish a re-run rather than a manual intervention against an immutable tag.
- **Publication is a single reviewed act.** Merging the release pull request into `main` publishes; credentials remain scoped to the `marketplace` environment and unreachable from any other job. `scripts/bootstrap-ci.sh` provisions the environment, both deployment refs, all six secrets, the signing key and its account registration in one idempotent run.
- **The GitHub Release carries `CHANGELOG.md`**; the Marketplace "What's New" panel continues to carry `RELEASE_NOTES.md`. An empty extraction fails the release.

### Continuous integration

- **Segmented CI images**: `node-test` (462 MB) for the npm jobs, `jvm-test` (8.08 GB) for the Gradle jobs, with the artifact-assertion and release-readiness jobs on bare runners. Container startup for the frontend suite is **10 s**, from 5m37s. The plugin verifier's IDEs are resolved at run time rather than baked, keeping them current with the EAP/RC channels they come from.
- **The Gradle cache in the image is genuinely warm**: the IntelliJ Platform is extracted at image-build time and the build is verified to compile **offline** from it. A warm-up failure fails the image build.
- **CodeQL is a required check on `develop` as well as `main`**, with the `java-kotlin` analysis running on the same JDK and warm cache as the rest of the pipeline.
- **A release-readiness gate** blocks `develop → main` while an automated pull request is open against `develop`, so a release cannot ship alongside an unmerged dependency update.
- Branch protection, deployment policy and required checks are versioned and applied by script.

## [4.4.1] — 2026-07-29

### Added
- **A one-time Marketplace review prompt** (`ui/ReviewPrompt`). After **25 successful turns** — errors and interrupts don't count — a single, non-modal IDE balloon asks for a review, then never appears again for that installation. Deliberately conservative: the counter and the "asked" flag live in application-level `PropertiesComponent` (so a second project neither resets progress nor produces a second prompt), the flag is written *before* the balloon is shown (a crash can't cause a double ask), and there is intentionally no "remind me later" — a deferral is a nag with extra steps. Rationale, measured on the live Marketplace `Claude Code` query: rating outweighs download count in ranking (a plugin with 8.6k downloads and a 4.54 rating outranks several with 4–10× the downloads, while 4.4M downloads at a 2.38 rating rank below them), so a *bad* rating is worse than none — which is why the prompt is built to be impossible to experience as nagging. The policy (`shouldAsk`/`recordTurn`) is pure and unit-tested, including that it fires exactly once when counting past the threshold.

### Changed
- **GitHub repository metadata for discoverability** — the repo had no topics and no homepage. Added 20 topics (`claude-code`, `anthropic`, `jetbrains-plugin`, `ai-agent`, `intellij-plugin`, the IDE names…), pointed the homepage at the Marketplace listing, and rewrote the repo description with the terms people actually search for.

### Fixed
- **A reloaded transcript rendered command cards in the pre-4.3.2 style — no code block, nothing to copy.** Restoring a past session reconstructs the transcript through `SessionTranscriptReader`, a code path entirely separate from a live turn, and it never populated the two inputs the current rendering needs: `commandText` on the `TOOL` row (so no copyable command block) and the `command` tag on the `TOOL_OUTPUT` row (so the output fell back to plain text). Both are now derived exactly as the live path derives them — `SensitiveGuard.commandText` for the call, and a post-parse pass (`tagCommandOutputs`) for the output, needed because the JSONL emits a `tool_result` in a later message than its `tool_use`, so at parse time the output's own line carries nothing identifying it as a command's. The `is_error` flag is read too, reproducing the live `"command error"` tag set. This is the second instance of the same class of bug — 4.3.1 fixed restored *file* cards losing their jump-to-code path — so the restore path now has regression tests pinning that it produces the same row a live turn does.

- **`/login` always dead-ended on "run this yourself in a terminal".** Every platform API `TerminalLauncher` reflected on to open the terminal tab was missing at runtime on a current IDE: the Reworked path looked up `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager`, which is **not present in the shipped IDE at all** (verified by scanning every jar of IU-262.8665.337), and the Classic path called `TerminalToolWindowManager.createShellWidget(…)` / `.createLocalShellWidget(…)`, both of which existed on 251/252 but were **removed by 262**. Each lookup returns `false` rather than throwing, so the failure was completely silent — nothing reached the log, the user just always got the manual-command notice. Replaced with `TerminalToolWindowManager.createNewSession(workingDirectory, tabName, shellCommand, requestFocus, deferSessionStartUntilUiShown)`, verified by hand to exist on **251, 252 and 262 alike**. The login is now passed as an **argv list** rather than a shell string, which also removes the shell-quoting hazard (the Windows PowerShell `&` prefix, paths with spaces) and the send-text-into-a-shell startup race that could swallow the command.
- **The native PTY sign-in was unreachable code.** `startLogin()` called the terminal path unconditionally, so `ClaudeLoginFlow` — the pty4j-based flow the KDoc and docs described as the primary path — was never invoked, and there was no fallback when the terminal failed. `/login` now tries the IDE terminal, then the native PTY flow (which needs no Terminal plugin at all), and only then the manual notice; each step is a real fallback rather than a dead end. Fixed a latent bug in that path while wiring it up: pty4j **replaces** the child environment wholesale (unlike `ClaudeProcess`, which inherits the parent's), so the base environment is now merged in — without it the spawned binary would have lost `PATH`/`HOME` entirely.

### Internal
- Added `TerminalApiContractTest`, which pins the `createNewSession` overload against the real platform on the build classpath so a future rename fails at build time instead of silently degrading. Documents why CI missed the original break: the plugin compiles/tests against IC-2025.2 (252), where the removed factories still exist, while the regression only manifests on 262+ — an asymmetry `verifyPlugin`'s range run is the complementary guard for.

## [4.4.0] — 2026-07-28

### Added
- **Per-rule security toggles (Settings ▸ Claude Code ▸ Security).** `SensitiveGuard`'s three categories — Credential/key material, Dangerous commands, and Foreign territory (now split into its three sub-rules: another user's home, network/UNC mounts, and foreign WSL drives) — are each independently switchable, all **ON by default** so a fresh install reproduces the original behaviour exactly. Turning a rule off is **never a silent allow**: `classify()` still runs unconditionally, and a hit is only downgraded from an automatic `DENY` to a permission card (`ASK`) — shown every time, to every caller, MCP servers and Skills included. A trusted agent tool that trips Credential/Dangerous-command already got a card either way; the toggle only ever changes what an *untrusted* caller gets. `SensitiveGuard.reason()` now always names where to change the rule ("… — disable this in Settings ▸ Claude Code ▸ Security"), whether the rule is currently enforced or already downgraded, so the lever is discoverable from the block/prompt itself. `Policy` gained five `enforce*` fields (all defaulting `true`); `ClaudeSettings` persists the five toggles and wires them through `sensitivePolicy()`.

### Fixed
- **Native CLI tools the plugin didn't know about were hard-denied like a blocked MCP server.** `SensitiveGuard.AGENT_TOOLS` — the allowlist of trusted, first-party callers — had gone stale as the CLI grew its own orchestration surface: the background-task family (`TaskCreate`/`TaskGet`/`TaskUpdate`/`TaskList`/`TaskOutput`/`TaskStop`), cron (`CronCreate`/`CronDelete`/`CronList`/`ScheduleWakeup`), worktrees (`EnterWorktree`/`ExitWorktree`), `EnterPlanMode`, `Agent` (a newer alias for `Task`), `SendMessage`, the MCP-resource-browsing tools (`ListMcpResources`/`ReadMcpResourceDir`/`ReadMcpResource`/`RefreshMcpTools`), and several more were all missing from the list — none of them are third-party (they're modeled in the vendored `@anthropic-ai/claude-agent-sdk` reference alongside `Bash`/`Read`/`Edit`, not user/community-authored like a Skill or an MCP server) — so tripping the Credential or Dangerous-command rule denied them outright instead of asking, indistinguishable from a genuinely blocked MCP call. `AGENT_TOOLS` now includes the full confirmed set; `Skill` and any `mcp__*`-prefixed name remain deliberately excluded (that content is third-party by design, unaffected by this fix). Regression test covers all 31 newly-trusted tool names.

## [4.3.3] — 2026-07-27

### Changed
- **The model picker is now fully driven by the binary's own catalog, with the version on every entry.** The list was already autodetected from the `initialize` handshake, but it labelled each model with the binary's `displayName` — which omits the version ("Opus (1M context)", "Sonnet") — so you couldn't tell Opus 4.8 from Opus 5 at a glance. Each entry now shows the versioned label the binary carries in its `description` ("Opus 5 with 1M context", "Sonnet 5", "Haiku 4.5"). The same label logic backs both the composer pill/menu and the Settings combo, so they never disagree.
- **The floating "default" alias is no longer offered as a selectable model, and the default is pinned to the concrete Opus tier.** The binary exposes both a `default` alias and the concrete `opus[1m]` value that it currently resolves to — the same model listed twice, the alias with no version. The alias is now filtered out of both selectors, and a fresh install defaults to the concrete Opus (`ClaudeSession.DEFAULT_MODEL`), so the choice stays on Opus even if the binary later re-points its recommendation. `preferredDefault` falls back to the binary's own recommended alias (then to the first listed model) if a binary ever ships without the pinned value, so the plugin never selects a model the binary doesn't offer. A legacy install with `default` persisted is migrated to the concrete tier on display/save.

### Fixed
- **Removed a hardcoded `"Default · Opus 4.8"` model label.** The composer pill fell back to that literal string whenever the selected model was unset or the `default` alias — which went stale the moment the recommended tier moved to Opus 5, showing "Opus 4.8" for what was actually Opus 5. The label is now always derived from the live catalog (or from the model id as a last resort), never a baked-in version.

### Internal
- Protocol drift baseline advanced to SDK `0.3.220` / `claude` `2.1.220` (`./gradlew checkDrift` green; protocol surface unchanged).

## [4.3.2] — 2026-07-23

### Added
- **The command a `Bash`/PowerShell/MCP-exec call runs is now its own copyable code block**, shown right under the tool card's header — visible without expanding the card, with a Copy button (`SensitiveGuard.commandText` detects it by input shape, not tool name, so any command-executing tool is covered). The header no longer crams the raw command text into the title; it just names the tool, and the card gets a distinct left-accent look (`cmd-tool`) so a command call reads as one at a glance.
- **Syntax highlighting for diffs and file output.** A `Read`/`Write`/`Edit`/`MultiEdit` card's plain output, and the coloured unified diff on a completed edit, are now syntax-highlighted from the file's extension (`CC.languageForPath`, ~35 languages from the vendored highlight.js bundle), layered under the existing added/removed line colouring for diffs. Falls back to highlight.js's own autodetection for an unrecognised extension.

### Security
- **Fixed a false positive that hard-denied ordinary `Edit`/`Write` calls.** `SensitiveGuard.isUnc` classified *any* string starting with `//` as a UNC network path — including an everyday `// some comment` line inside an `Edit`'s `old_string`/`new_string` (`pathCandidates` walks every string leaf of the input, not just recognised path keys). That misclassified the call as **foreign territory**, which denies outright regardless of caller trust — so editing a file with a `//`-style comment on the touched line could get silently refused, with no setting to override it. Fixed: `isUnc` now requires the segment right after the leading `//` to be a whitespace-free, non-blank host name, which a real UNC path always has and a comment line never does. Regression tests added.
- **Fixed a crash in the sensitive-command classifier.** `SensitiveGuard.substituteAssignments` passed a shell-assigned value straight to `String.replace(Regex, String)`, which treats its second argument as a *replacement template* (`$1`/`${name}` are group references, not literal text) — a value containing `$`/`${...}` (e.g. `k=${OTHER}/x`) threw an uncaught `IllegalArgumentException: Illegal group reference` from deep inside `java.util.regex.Matcher`, confirmed live via a stack trace, crashing `verdict()` for that `Bash` call with no response ever sent back to the binary. Fixed with `Matcher.quoteReplacement`, so the value is always substituted literally. Regression test added.
- **WSL: the security layer no longer refuses to start on a `/mnt/c` project.** WSL2 mounts the Windows `C:` drive over 9p, which is in `RemoteMounts.REMOTE_FS_TYPES`, so `detect()` put `/mnt/c` into `remoteRoots` and the startup gate (`RemoteMounts.isRemote`) treated a normal `C:\` project as a network share and aborted the launch; the same `remoteRoots` also fed `SensitiveGuard`'s foreign-territory rule. Fixed in two layers: `detect()` no longer treats any `/mnt/*` mount as a generic remote root under WSL (those are governed by the dedicated `/mnt/c` rule), and `isRemote` exempts `/mnt/c` (and its subtree) before the fstype checks as defense in depth. Every other `/mnt/*` drive stays foreign. Regression tests added (`RemoteMountsTest`).

## [4.3.1] — 2026-07-14

**Jump to code from the conversation**, a chat tab that actually takes the keyboard focus, and an IDE that sees Claude's writes as they happen.

### Added
- **Jump-to-code links in the transcript.** A file tool's card names its file **relative to the project** (`Read(src/main/kotlin/permission/PermissionBroker.kt)`, not a bare file name) and the path is clickable: it opens in the editor at the right line and is selected in the Project view. In model text, **paths** (`src/Foo.kt`, `a/b.py:42`, `~/.claude`), **directories** (revealed and expanded in the Project view — or opened in the OS file manager when they live outside the project) and **symbols** (`PermissionBroker`, resolved through *Go to Symbol*, so it works in every JetBrains IDE, not just the Java/Kotlin ones) become links as well. A bare file name resolves too (`app.css:190` — via the IDE's file index, plus a bounded on-disk scan for *excluded* folders like `build/`, which no index knows about), and archives reveal in the tree instead of opening a useless binary buffer.
- Nothing is linked on a guess: the IDE confirms every candidate first, and **only an unambiguous match links** — two `app.css` in the tree means no link at all, rather than a jump to an arbitrary one. Anything unresolvable stays plain text, so a link is never dead.

### Changed
- **Compatibility floor lowered to build 251 (2025.1)**, from 252. That is as far back as the plugin reaches while shipping **zero deprecated API**: `FileChooserDescriptorFactory.multiFiles()`/`singleDir()` (the Attach file picker) simply does not exist on 2024.2/2024.3 — verified, `NoSuchMethodError` — and its pre-251 equivalent is deprecated on current IDEs. Reaching 2024.x would need a separately targeted build, which is JetBrains' documented approach for a range where the API actually changed. Verified **Compatible** on IC-251, IC-252, IU-253, IU-261 and IU-262, with no internal-API and no deprecated-API usage.
- `verifyPlugin` can now run fully **offline**: `-PlocalIdePath` (and `LOCAL_IDE_PATH`) accept a **comma-separated list** of extracted IDEs, so the whole declared range can be verified without reaching `download.jetbrains.com` — which matters, because the verifier is the only thing standing between a clean compile and a `NoSuchMethodError` in a user's IDE.

### Security
- Added `permission/SensitiveGuard`, a deterministic pre-authorization gate for `can_use_tool`. It is evaluated in `PermissionBroker.handle` **before** any auto-approval branch, so it applies regardless of permission mode (`default`/`acceptEdits`/`bypassPermissions`) and regardless of "Always allow" — the binary is always launched in `default` mode (`SessionLauncher.binaryPermissionMode`), so every call is delivered as a control request and the verdict is the plugin's to make. Not a model-side guardrail: the classification is out-of-band Kotlin with no model input.
  - **Classification (three categories).** *Credential/key material* — SSH/GPG/PKI, cloud/cluster/container credentials, DB and shell-history secrets, browser and password-manager stores, crypto wallets, and AI-agent/code-host access tokens — matched by structural globs (`**/…`) rather than `$HOME`-anchored, so native, macOS, Windows (`C:\Users\*`) and WSL (`/mnt/c/Users/*`) paths resolve to one rule. *Dangerous commands* — credential dumps, file exfiltration, reverse shells, LOLBINs, recognised offensive tooling — matched against a curated regex set. *Foreign territory* — another user's home (`/home/*`, `/Users/*`, `/root`), UNC (`\\host\share`), a network mount (fstype ∈ {nfs, cifs, sshfs, …} via `RemoteMounts`), or under WSL any `/mnt/*` ≠ `/mnt/c`.
  - **Input coverage.** The full input object is walked for path-like string leaves (not a fixed key list), so an MCP tool naming its argument `path`/`target`/`destination`/… is covered. Command strings are extracted from command-shaped keys and `argv` arrays.
  - **Evasion resistance.** Path candidates are canonicalized on disk via an injected resolver (symlink and `..` targets), and command strings pass a de-obfuscation stage (quote-splitting, `$IFS`, single-token variable substitution, `base64`-payload decode) before matching. Both raw and normalized forms are evaluated. Detection of paths inside arbitrary shell strings is best-effort by design; enforcement of a match is not.
  - **Verdict matrix (allowlist by caller).** Trusted caller = the agent's built-in tools only. Credential/command hit → trusted caller **ASK** (card shown in every mode); untrusted caller (MCP/Skills/unknown) **DENY**. Foreign-territory hit → **DENY** for all callers. No setting relaxes these; the only user knob is `sensitiveExtraGlobs`, which is additive to the built-in blacklist.
  - **Scope exemption.** Paths under the project root are exempt from the credential and foreign-territory rules (the sanctioned working zone); dangerous-command classification is location-independent.
  - **Startup gate.** `ClaudeSession.start` refuses to launch when `project.basePath` resolves to a remote/network/foreign mount (`RemoteMounts.isRemote`), surfacing an error notification instead of spawning the process.
  - Covered by 35 unit tests (`SensitiveGuardTest`, `SensitiveGuardEvasionTest`, `RemoteMountsTest`), including negative cases for ordinary development. See `SECURITY.md`.
- Jump-to-code links are gated by `LinkResolver.isOpenable`: a link can only ever point **inside the project or inside the user's own home** — never at `/etc/passwd`, never at another user's files, not even through a symlink (the check compares *canonical* paths). The **write** gate is untouched: what the binary may write stays confined to the project root.

### Fixed
- **A chat tab could come up unusable — the composer refused to take the keyboard focus** (a newly opened tab, and sometimes the tabs restored at IDE start); the only cure was closing and reopening the tool window. Two independent causes: the tab never declared *where* its keyboard focus lives (`Content.preferredFocusedComponent`, which must point at CEF's real input component — `JBCefBrowser.getComponent()` is a wrapper panel and is not focusable), and a raw AWT `requestFocusInWindow()` is refused outright while the IDE's own `IdeFocusManager` is settling focus (measured: denied 34 times in a row on a fresh tab). The focus is now transferred by the `ContentManager` as part of selecting the tab (`setSelectedContent(content, requestFocus = true)`) — the same path a manual tab switch takes.
- **No caret in a new chat tab**, even though the keystrokes were arriving. CEF keeps its own focus flag, and a freshly loaded page starts with it cleared — while the browser takes the focus ~500 ms *before* its page exists. It is now told it has the focus once the chat has actually announced itself (`JcefHost.markWebReady`), which is when there is a caret to paint.
- **The IDE only saw Claude's writes at the end of a turn.** Until then the editor showed stale contents and a jump-to-code link on a freshly written file opened nothing at all — the file did not exist for the IDE yet. Every successful write now refreshes the VFS immediately: by exact path for `Edit`/`Write`, and by re-scanning the project tree after a `Bash` command or a file-mutating MCP tool, which can change anything. Newly **created** files are picked up too — refreshing a file the VFS has never heard of is a no-op, so its parent directory is re-scanned as well.
- **Restored sessions showed absolute paths on their tool cards, with no links.** `SessionTranscriptReader` rebuilt the transcript without the project root, so reopening the IDE turned every card into a bare absolute path. A separate code path from a live session — and it had been missed.

### Internal
- `PluginId.getId(…)` is gone. `PluginId` became a Kotlin class in 2025.2, so compiled against that SDK the call binds to `PluginId.Companion` — a symbol that does not exist in older IDEs, i.e. a `NoSuchFieldError` waiting to happen anywhere below 252. The id now comes from the plugin descriptor (`InstalledPlugins`), which reads the same on every IDE. Caught by `verifyPlugin` against IC-251, not by the compiler: it is a *binary* incompatibility, not a source one.
- `LinkResolver.resolveSymbols` moved off the deprecated `ReadAction.compute` to `ReadAction.nonBlocking(…).inSmartMode(project)`, which also fixes a real bug: the *Go to Symbol* index does not exist while the IDE is indexing, so symbols would have silently resolved to nothing.
- Tests: `+37` (Kotlin **588**, frontend **32**, 0 failures). The new `LinkGateTest` covers the security boundary as a boundary — project file, home file, `/etc/passwd`, `/usr`, another user's home, a `../../..` traversal and a **symlink pointing at `/etc`** — and the frontend suite now guards the CSS-specificity trap that painted jump-to-code links in the accent colour inside model text while the identical ones on tool cards came out blue.

## [4.2.0] — 2026-07-08

**Protocol upgrade to `claude` 2.1.204 / SDK 0.3.204** — `./gradlew checkDrift` flagged five new protocol kinds; reconciled and re-verified green at the new baseline.

### Added
- **`system/background_tasks_changed`** is now modeled and surfaced as a **"Background tasks"** card in the session dashboard (with Stop). It's a **level** signal — the binary re-sends the *full* live set on every membership change — so unlike the edge-derived Subagents list it can never wedge a stale "running" indicator on a missed start/stop bookend. Kept deliberately separate from the subagent stream (the SDK leaves their relative ordering unspecified and forbids correlating them); reset to empty whenever the CLI process restarts.
- **`system/control_request_progress`** is now modeled: progress for a host-originated control request (currently `side_question`, i.e. `/btw`). An `api_retry` status carries the same retry counters as `system/api_retry` and is surfaced the same way instead of being dropped; `started` is logged.

### Fixed
- **Empty "Thought process" fold on Opus 4.8.** Newer models emit **redacted** thinking: the block streams only a `signature_delta` and every `thinking_delta` carries an *empty string* (verified on the wire: 4/4 deltas empty, finalized block `len=0`). `str()` returns `""` — which is not `null` — so the parser's unguarded `?.let` emitted a delta and opened a "Thought process" fold with nothing in it, which never filled. Empty thinking deltas no longer produce an event, a blank delta never opens a fold, and a blank finalized block never blanks out reasoning that did stream. There is simply no fold when there is no reasoning text to show.
- **MCP servers card layout was broken.** `.mcp-actions` had **no CSS rule at all**, so the *Reconnect* button and the enable/disable switch wrapped onto separate lines and overlapped; and the switch (a 32×18 pill whose knob is an absolutely-positioned `::after`) was given a text label, so the knob painted on top of it (`Dis●ble`). The actions row is now a proper flex row, the switch is a switch (state via `role="switch"`/`aria-checked`, name via `title`/`aria-label`), and the server name gets `min-width: 0` so it ellipsizes instead of shoving the buttons out of the row at narrow widths.

### Internal
- Triaged three thin-client host→binary control requests the plugin knowingly does not send — `list_models` (models come from the `initialize` reply), `get_plan`, `get_workspace_diff` — into `ProtocolSurface.KNOWN_SUBTYPES`.
- Baseline bumped to `sdk=0.3.204` / `binary=2.1.204`; `checkDrift` green.

## [4.1.0] — 2026-06-27

### Added
- **Editable diff review for edits.** When Claude asks to Edit/Write/MultiEdit a file, the plugin now **auto-opens an editable diff** in the IDE editor (Current | Proposed) — not just in acceptEdits/bypass mode. The proposed side is created with `DiffContentFactory.createEditable`, so you can **tweak the proposed content right in the editor** before approving; **Accept writes your edited version** (the tool input is re-encoded via `HunkSelection.encodeInput` so the binary writes exactly what you left), and the diff **closes automatically** on accept/reject. The captured snapshot is repointed at the effective input, so the transcript's inline diff and **"View diff"** reflect what was *actually* written (your edit), not Claude's original proposal. Fail-safe: if you change nothing — or the platform renders the proposed side read-only — Accept falls back to writing Claude's original proposed content, never a wrong write. Review diffs are also closed on stop/interrupt/dispose.

## [4.0.5] — 2026-06-27

### Changed
- **Permission cards for edits now show a read-only diff instead of per-line checkboxes.** The previous hunk-by-hunk partial-acceptance UI (a checkbox per changed region) rendered as a confusing checklist and, worse, let you apply an incoherent subset of an edit — a reliable way to produce broken code. Edits are now **atomic**: the card shows a proper colour-coded unified diff (red removed / green added) and you accept or reject the whole change. The full diff is still available via **View diff** and the IDE's auto-opened diff tab. The partial-accept plumbing (`hunkCache`, per-hunk encode/reconstruct in the card path) was removed.

## [4.0.4] — 2026-06-26

A broad bug-fix + UX pass (the `claude` binary auto-updated to **2.1.193** in the meantime; protocol re-baselined).

### Fixed
- **Interrupt never actually stopped.** Esc / the Stop button sent the `interrupt` control request fire-and-forget with no response handler, so the binary's ack was discarded, `turnActive` never cleared, and the "Interrupting…" line — added as a permanent transcript row — re-rendered on every state push (the looping "Interrupting" the turn never escaped). Interrupt now goes through the correlated control client (clears the turn on ack/timeout), shows a transient **Interrupting…** state on the Stop button (no transcript spam), **flushes the queued prompts** so it can't immediately re-pump a new turn, and clears pending permission cards.
- **Chat dead on first open** (had to close & reopen the tab). The JS `ready` handshake could fire before the host injected `window.__ccSend`, dropping the message; and a process-global scheme race could serve a blank page. The handshake is now self-healing: the web app retries `ready` until the bridge exists, and the host reloads via `loadHTML` if the page doesn't come alive shortly after load.
- **User prompts were rendered as Markdown.** A prompt containing `*`, `#`, backticks or indentation got mangled. User messages now render **verbatim** (plain text, whitespace preserved); Markdown rendering is reserved for model output.
- **Dead "Copy" button** on model code blocks — the per-block click listener was lost when the decorated fragment was serialized to HTML. Copy is now a delegated handler (click + keyboard).
- **"Thought process" duplicated / out of order.** A finalized thinking block was appended as a second, post-answer entry instead of replacing the streamed one. Reconciliation now tracks the message's thinking entry and replaces in place.
- **Menu flicker + de-selection while streaming.** The composer rebuilt pills and closed/reopened the open menu on every (frequent) state push. It now updates incrementally and only rebuilds an open menu when its selection actually changed. The transcript no longer re-serializes the whole conversation on every appended row (was O(N²)).
- **Two checkmarks** on the selected item in prompt menus (CSS `::after` + a JS span) — now one.
- **Esc closing the find bar also interrupted the turn** — the find-bar Escape now stops propagation.
- **"Always allow" could approve the wrong pending card** (it matched by tool name); it now resolves the exact card. **Accepting zero hunks** is treated as a deny (it used to send a no-op edit the model saw as an error).
- **Permission re-push wiped in-progress card state** (typed elicitation fields, question selections, unticked hunks) — cards are now reconciled by id.
- **Session dashboard** layout was broken (missing `.dash-inner` grid wrapper; the overlay covered the composer) and now lays out correctly without hiding the composer.
- **Clipboard paste froze the IDE** on a slow/hung clipboard owner (the Wayland `wl-paste`/`xclip` read ran on the EDT with an unbounded read). Reads now run off-EDT with a deadline.
- **Find bar** now scrolls to the active match and supports Enter / Shift+Enter navigation with an `i / n` counter.

### Changed
- **Adaptive thinking is ON by default** for new installs.
- **Vibe Mode** rainbow is ~3× faster (and coherent between the JCEF and Swing sides).
- **Responsive UI**: the composer control bar wraps instead of clipping pills, the find bar and chips are fluid, and chat tab titles are truncated (full title in the tooltip) so many open chats don't push the tab strip off-screen.

### Internal
- **Latent concurrency/lifecycle fixes:** a `starting` guard + generation re-checks prevent a double `claude` spawn and an orphaned process when a tab is closed mid-launch; `dispose()` now bumps the generation (no spurious "exited unexpectedly"); a malformed `can_use_tool` can no longer throw and hang the turn (it replies with an error); the `ToolWindowFactory` no longer caches a per-project window in shared state.
- **Protocol re-baselined to `claude` 2.1.193 / SDK 0.3.193** — models the new `system/informational`, `model_refusal_no_fallback` and `worker_shutting_down` subtypes; `./gradlew checkDrift` green.

## [4.0.3] — 2026-06-10

### Fixed
- **Composer clipboard paste on native-Wayland IDEs (the real fix).** 4.0.2 added a host-side `wl-paste` *read* fallback, but `Ctrl+V` still did nothing — because the bug is the **trigger**, not the read. Under the native Wayland toolkit (`sun.awt.wl.WLToolkit`) the embedded **CEF browser's web clipboard is isolated from the system clipboard**, so the composer's `paste` event only ever saw content copied *inside* the web view, and never reached the host. `Ctrl+V` now routes through the host whenever the Wayland toolkit is active (a `hostClipboard` flag in the meta payload): the paste handler ignores CEF's isolated `clipboardData` and the host reads the real clipboard via `wl-paste`/`xclip` — the same path the **Attach → Image** button already used successfully. Text and image paste from external apps now work, as does pasting back what a Copy button placed on the system clipboard.
  - Diagnosis (confirmed live): even the IDE's **own editors** can't read the external clipboard under this JBR (AWT/`CopyPasteManager` *reads* are broken on native Wayland — a focus-gated protocol limitation), so `wl-paste` (the `data-control` protocol) is the only mechanism that reaches the Wayland clipboard for reads. Clipboard *writes* (the Copy buttons → `CopyPasteManager.setContents`) already worked.

## [4.0.2] — 2026-06-10

### Fixed
- **Text paste broken on native-Wayland IDEs.** On IntelliJ 2026.1+ running the native Wayland toolkit (`sun.awt.wl.WLToolkit`), AWT's clipboard is empty/unreliable, so `Ctrl+V` of **plain text** into the composer did nothing (image paste already worked — it had a `wl-paste`/`xclip` fallback; text didn't). Text paste now falls back to the same host-side CLIs, reading a real `text/*` target. The selection is guarded (`preferredTextType`) so an **image-only** clipboard — e.g. a KDE screenshot, where a blind `wl-paste -n` emits raw PNG bytes — is never mis-read as text, and `text/uri-list` (a copied file) and `text/html` (markup) are excluded from the plain paste. X11/XWayland and Windows/macOS are unaffected (AWT works there, so the fallback never triggers).

## [4.0.1] — 2026-06-10

**Protocol upgrade to `claude` 2.1.170 / SDK 0.3.170** — `./gradlew checkDrift` flagged four new protocol kinds; reconciled and re-verified green at the new baseline.

### Added
- **`system/model_refusal_fallback` handling.** When the primary model ends a turn with stop_reason `refusal`, the binary now retries once on a fallback model and emits this system message. The plugin models it (`ModelRefusalFallbackInfo`) and surfaces a transcript notice ("The model declined to respond (\<category\>) → retried on \<fallback-model\>.") instead of silently dropping the frame. Previously the parser left it as `Other`, so a refusal-and-retry was invisible.

### Changed
- **Drift baseline → `claude` 2.1.170 / SDK 0.3.170.** Triaged the three new host→binary control requests `get_usage`, `register_repo_root`, and `reload_skills` into the known protocol surface (`ProtocolSurface.KNOWN_SUBTYPES`) — the plugin doesn't send them yet, but they're no longer reported as drift. `checkDrift` is green at the new baseline.

## [4.0.0] — 2026-06-04

**Chat UI rebuilt on JCEF (embedded Chromium), then hardened and extended — all frontend; the Kotlin backend was untouched.** See `RELEASE_NOTES.md` for the full story.

### Added
- Embedded-web chat (JCEF): streaming transcript, web composer, native permission/question/elicitation cards, session dashboard, strict hash-pinned CSP.
- **Hunk-by-hunk partial diff acceptance** — checkbox per changed region on reviewable Edit/Write/MultiEdit cards.
- **`jb://` jump-to-code links** — `@file` mentions open the file at the line, gated to the project root.
- **Rich attach menu** — search + Files/Directory/Image + current selection/file + filterable Recent files.
- **Syntax highlighting in the IDE's colours** (highlight.js classes mapped to the editor scheme).
- **Native rewind** as the default rollback (`rewind_files` by turn) with a confirmed IDE-side per-file fallback.
- Clipboard paste on Wayland (text via AWT, image via `wl-paste`/`xclip`), tool-card colour states, colourised inline diffs, Ctrl+O reasoning toggle, auto-follow, 🌈 Vibe Mode, inline images, responsive layout.

### Changed
- The old Swing chat UI (`ChatPanel`/`TranscriptView`/`MarkdownRenderer` + tray/strip panels) and its tests were removed.
- ⚙ menu reuses the formatted JCEF dashboard instead of plain-text dialogs.
- Migrated the rewind-fallback confirmation off the deprecated `Messages.showYesNoDialog(…DoNotAskOption)` overload to `MessageDialogBuilder.yesNo` (keeps the zero-deprecation build clean).

### Fixed (post-rewrite expert-consensus review)
- **Startup crash / "all sessions disappeared" regression** (introduced by the `hunkCache` leak fix below, before release). The new unconditional `hunkCache.keys.retainAll(…)` prune in `pushPermissions()` dereferenced `hunkCache`, but that field was declared *after* the `init {}` block that calls `pushPermissions()` — and Kotlin initializes properties in declaration order, so the field was still `null` during construction → `NullPointerException` in `JcefChatPanel.<init>`. Every chat tab (including each restored session on startup) failed to construct, leaving the tool window empty (sessions on disk were untouched — the binary's JSONL files are the source of truth). The field declaration moved above `init {}`. The earlier code only touched `hunkCache` inside the `computeHunks` loop, which is empty at startup, so the null-deref stayed latent until the unconditional prune was added.
- **Hunk-by-hunk partial accept no longer writes from a stale snapshot.** On accept the file is re-read from disk; if it diverged since the card was shown, the plugin falls back to a normal full accept instead of reconstructing from the cached line snapshot (which could silently no-op or clobber an external change).
- **`hunkCache` can no longer leak.** Cached hunk contexts are pruned to the still-pending permissions on every push and cleared on panel dispose, so permissions cleared on stop/interrupt (without an explicit resolve) don't accumulate.
- **Large files skip the EDT-side hunk read/diff** (>1 MB) — hunk-by-hunk review is meaningless there and the synchronous read would freeze the UI; full accept still works.
- **Restored the `sms:` URI scheme** in the DOMPurify allowlist (it was dropped when the explicit `ALLOWED_URI_REGEXP` replaced DOMPurify's default; `data:image/` inline images and the internal `jb:` scheme remain allowed, `data:text/html` stays blocked).

### Notably not added
- Mermaid / KaTeX — avoided as external bloat that would force relaxing the strict CSP. The plugin stays lean (~1.6 MB).

## [3.3.0] — 2026-06-04

**Completes the binary→host protocol surface.** Every message and control request the `claude` binary sends the host is now both *parsed* and *used*: the two control requests that were previously rejected with an error are answered correctly, and every event that was parsed-but-only-logged is now surfaced in the GUI. After this release nothing the binary emits to the host is silently dropped or wrongly errored — it is acted on (when it is a request) or shown (when it carries information). A new on-demand **drift detector** keeps these native models in lock-step as the binary and its SDK evolve, with `KNOWN_SUBTYPES` now tracking the **full triaged 0.3.162 subtype surface** (receive + send + knowingly-triaged).

### Added — protocol correctness (binary→host control requests)
- **MCP elicitation — native input cards.** When an MCP server asks the user for input (`elicitation`), the plugin surfaces it as an **inline non-modal card** in the permission tray (never a blocking dialog), reusing the same `PendingPermission` pipeline as the question/plan cards. **URL mode** (e.g. an OAuth flow) shows an **Open link** button + Accept/Cancel; **form mode** renders a labeled input per primitive field of the server's `requested_schema` (string/number/integer/boolean — extracted by the pure `parseElicitationFields`) and returns the collected `content` on Accept, else Decline; a non-renderable schema degrades to a plain Accept/Decline. Replies with an `ElicitResult` (`{action, content?}`). Previously rejected with an error. Tearing down a session with a card pending **default-cancels** it (while the process is still alive) so the binary is never left waiting. (`protocol/Protocol.kt`, `protocol/ClaudeEvent.kt`, `protocol/ControlProtocol.kt`, `permission/PermissionBroker.kt`, `ui/PermissionTrayPanel.kt`, `session/ClaudeSession.kt`.)
- **`request_user_dialog` answered correctly.** A tool-driven blocking dialog of an open-union kind the host doesn't render is now answered `{behavior:"cancelled"}` (the CLI then applies the dialog's own default) with a brief transparency note, instead of an error reply. The pure `DialogResponder` owns the reply + note so it stays unit-testable. (`protocol/DialogResponder.kt`, `protocol/ClaudeEvent.kt`, `protocol/ControlProtocol.kt`, `session/ClaudeSession.kt`.)

### Added — surfacing previously parsed-but-hidden events in the UI
- **Predicted next-prompt chip.** The binary's `prompt_suggestion` now appears as a dismissible `💡` chip above the composer; clicking it fills the input (you review/edit — never auto-sent), and it clears on send / dismiss / new turn. (`ui/SuggestionStripPanel.kt`, `ui/ChatPanel.kt`, `session/ClaudeSession.kt`.)
- **Live reasoning-token estimate.** The composer status line shows the running `thinking_tokens` estimate mid-turn (e.g. "Pondering… · ~1.2k reasoning tokens"), bucketed so it doesn't flicker, and reset at each message boundary and on teardown. (`session/StatusLineFormatter.kt`, `ui/ChatPanel.kt`, `session/ClaudeSession.kt`.)
- **Native hook execution rows.** The binary's hook telemetry (`hook_started` → `hook_progress` → `hook_response`) is narrated as **one evolving transcript row per hook** (running, with the latest output line → ✓/✗ on completion), keyed by hook id so a chatty hook can't flood the transcript. Distinct from `HookBroker`, which answers the `hook_callback` *control request*. (`session/HookActivityNarrator.kt`, `session/ClaudeSession.kt`.)
- **Memory-recall row.** `memory_recall` surfaces as a collapsible "Recalled N memories" row listing each recalled memory (scope · path + snippet), so it's visible what context influenced the turn. (`session/MemoryRecallFormatter.kt`, `ui/ChatMessageViews.kt`, `session/TranscriptModel.kt`, `session/ClaudeSession.kt`.)
- **Tool-use summary + file-upload notices.** `tool_use_summary` renders as a quiet dim note; `files_persisted` now also confirms successful uploads (not only failures). (`session/ClaudeSession.kt`.)

### Added — protocol drift detection
- **`./gradlew checkDrift`** — an on-demand Kotlin task that **updates the vendored SDK + the `claude` binary to latest first** (`npm update` + `claude --update`), then diffs the live protocol surface (subtype literals from `sdk.d.ts` + a one-turn binary probe) against the plugin's triaged `KNOWN_EVENT_TYPES`/`KNOWN_SUBTYPES`, printing an agent-consumable markdown report and **failing on actionable drift** (a bare version bump with a covered surface passes). Pure extraction/diff is offline unit-tested; the live half is tagged `driftLive` and excluded from the normal `test` task. Runbook in `docs/DRIFT_DETECTION.md`. (`src/test/.../drift/`, `scripts/drift-baseline.properties`, `build.gradle.kts`.)

### Changed — internals & architecture
- **New single-responsibility collaborators**, keeping `ClaudeSession` a thin delegating orchestrator (no god-object regrowth): `HookActivityNarrator` (hook-row state machine), and the pure `MemoryRecallFormatter` / `StatusLineFormatter` / `protocol/DialogResponder`. The `onEvent` dispatch now routes `MemoryRecall`, `PromptSuggestion`, `ThinkingTokens`, `HookStarted/Progress/Response`, `ToolUseSummary`, `FilesPersisted`, `UserDialogRequest` and `Elicitation` to these instead of `log.debug`. (`session/`.)
- **New composer sub-panel** `SuggestionStripPanel` (autonomous, like `QueueStripPanel`); **new transcript kind** `Speaker.MEMORY` + a collapsible `MemoryRow` (its own toggle, **not** driven by Ctrl+O); `PermissionTrayPanel` gains an elicitation-card branch and `PendingPermission` carries an optional `ElicitationCard`. (`ui/`, `permission/PermissionBroker.kt`, `session/TranscriptModel.kt`.)
- **Protocol baseline → SDK `0.3.162` / `claude` `2.1.162`**, and `KNOWN_SUBTYPES` expanded to the full triaged surface (every subtype the plugin parses, answers, sends, or knowingly leaves as `Other`/`UnsupportedControlRequest`). (`src/test/.../drift/ProtocolSurface.kt`, `scripts/drift-baseline.properties`.)

### Security
- **MCP elicitation URLs are scheme-restricted.** An MCP server is untrusted, so a `url`-mode elicitation link is opened only when it is `http`/`https` — `file:`/`jar:`/`javascript:`/UNC and other schemes are never handed to the browser launcher (gated both in the tray, which won't even offer the button, and at the `BrowserUtil.browse` call site, mirroring the link-scheme allow-list used elsewhere in the UI). Form-input values are built as a plain `content` object of the user's typed values; the reply `action` is constrained to accept/decline/cancel. (`ui/PermissionTrayPanel.kt`, `ui/ChatPanel.kt`.)

### Tests
- New: control-protocol builders (`userDialogCancelled`/`Completed`, `elicitationResult`), control-request parsing (`request_user_dialog` + `elicitation`, malformed→fallback), `parseElicitationFields` (primitives / nested→empty / null→empty), `DialogResponder`, `StatusLineFormatter`, `MemoryRecallFormatter`, the `HookActivityNarrator` state machine, and a headless `ClaudeSession` event-surfacing suite via the `handleEventForTest` seam. Full non-UI pyramid green; `verifyPlugin` Compatible across IC-251/252 and IU-253/261/262-RC.

## [3.2.1] — 2026-06-04

### Added
- **API provider selector (Anthropic / DeepSeek)** — a new `Provider:` setting (Settings ▸ Claude Code) and a composer chip pick the endpoint the `claude` binary talks to. **Anthropic** uses the binary's own native login (subscription/OAuth). **DeepSeek** routes to its Anthropic-compatible endpoint (`https://api.deepseek.com/anthropic`) and **requires its own issued key**. Each provider keeps an **isolated** API key in the IDE **password safe** (not in `claude-code.xml`), shown with its **brand logo** on the chip and in the menu. (`settings/Provider.kt`, `settings/ClaudeSettings.kt`, `ui/ClaudeSettingsConfigurable.kt`, `ui/OptionMenus.kt`, `ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/provider-*.svg`.)

### Fixed
- **Reasoning toggle now persists across turns** — new "Thought process" blocks correctly inherit the Ctrl+O toggle state instead of always appearing expanded. Previously toggling reasoning off hid existing blocks but every new turn's reasoning popped open again. (`ui/TranscriptView.kt`)

### Security
- **Credentials are pinned to their provider — no Anthropic credential ever leaks to a third party.** Switching provider sets `ANTHROPIC_BASE_URL` **and** `ANTHROPIC_API_KEY` as an **atomic pair**, and ONLY when a key is present (never a lone base URL, which would make the SDK ship your Anthropic OAuth bearer to the other endpoint). Because `ANTHROPIC_API_KEY` is set, the binary's SDK does not even load the stored OAuth `credentials.json`, so the subscription can't be sent elsewhere. We never emit `ANTHROPIC_AUTH_TOKEN`. The settings form rejects an Anthropic-shaped key (`sk-ant-…`) for a third-party provider; selecting a third-party provider with no stored key **does not switch or restart** — it prompts to configure the key first. **`/login` is restricted to the Anthropic provider** (a third-party auth failure is a wrong key, not a missing OAuth login). The pure `Provider.launchEnv` rules are unit-tested. (`settings/Provider.kt`, `session/ClaudeSession.kt`.)

## [3.2.0] — 2026-06-04

### Added
- **Two-row, adaptive options bar** — the composer controls are split into row 1 (model · mode · effort · thinking pills, centred) and row 2 (the toggles + attach, centred, with the Play/Stop button right-aligned). Each pill is a flat **capsule** with its own category glyph and a coral hover glow; the value label is now just the live value (full name in the tooltip). The Send control is a thin **neon outline** glyph (triangle/stop-square) stroked in the accent. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/chip-*.svg`.)
- **Coral focus ring + editor-font prompt** — the composer card lights its border coral (with a soft halo) while the prompt is focused, and the prompt now uses the IDE **editor font** (typically a mono) at the UI-scaled size, for a code-native feel. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`.)
- **Output follow toggle** — a follow button (coral while active, on by default) force-follows the streaming bottom even if you scroll up; off, the transcript still follows naturally while you're parked at the bottom so you can read history mid-stream. (`ui/ChatPanel.kt`, `ui/TranscriptView.kt`.)
- **🌈 Vibe Coder Mode** — an opt-in gag toggle that animates the coral accent through the rainbow: the send glyph, option pills, every bordered box, the tool/chip/attach **icons** (retinted live), the prompt's vibe ring, and the avatar (a **Nyan Cat**, with the tool-window stripe icon swapped to match). Off by default; one timer, stopped on dispose. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/chip-follow*.svg`, `resources/icons/claude-vibe.svg`.)
- **Composer paste fixed (Ctrl/Cmd+V)** — pasting an image into the composer now works, including the Linux case (Wayland over XWayland / X11) where a clipboard image arrives as a raw `image/…` flavor (`InputStream`/`byte[]`) rather than `DataFlavor.imageFlavor`. The keyboard paste is bound through the **IDE action system** (a `DumbAwareAction` on the platform `$Paste` shortcut via `registerCustomShortcutSet`, so it honours the user's keymap and is correct on every OS), and clipboard access goes through the cross-platform `CopyPasteManager`. A latent bug where the drag&drop `TransferHandler` captured a null delegate (breaking text paste/drop) is also fixed. New pure, unit-tested `context/ClipboardImageReader` centralizes raw-image extraction; the rendered-`java.awt.Image` fallback stays in the composer. (`ui/ChatPanel.kt`, `context/ClipboardImageReader.kt`.)
- **Richer attachment menu** — the attach menu now offers, besides current file / selection / clipboard image: **Add files…** and **Add directory…** (native `FileChooser`), plus **Add open files…** and **Add recent files…** submenus (from `FileEditorManager`/`EditorHistoryManager`). File chips use a **root-relative** label so same-named files in different folders no longer collide on dedupe. New `context/FilePickerHelper` (the pure `displayName` is unit-tested). (`ui/ChatPanel.kt`, `context/FilePickerHelper.kt`.)
- **Native visual identity** — a custom 16×16 SVG icon set replaces the borrowed `AllIcons` on tool-call rows (bash/read/edit/search/web/task/generic), the attach button, and attachment chips; file attachments show their **real file-type icon**. Attachment chips gain hover highlight, hand cursor, a custom ✕, and **click-to-open** in the editor (project-confined). (`resources/icons/*.svg`, `ui/ChatTheme.kt`, `ui/ChatMessageViews.kt`, `ui/AttachmentStripPanel.kt`, `ui/ChatPanel.kt`.)
- **Diff History tab + rollback** — a toolbar action opens a **Diff History** tab listing every reviewable Edit/Write/MultiEdit in the session (root-relative path, tool, native `+a/-b` summary) with **View diff** and per-edit **Revert**, plus a header **Roll back all changes**. Revert is IDE-side and **path-confined** (a `WriteCommandAction` restores the captured pre-write `beforeText` via VFS only when inside the project root), refreshes the VFS, and **reseeds the binary's read-state** (`seed_read_state`) so its next Edit re-validates against the rolled-back contents. A **Revert** button also appears on each reviewable transcript row beside *View diff*. The enumeration + rollback live in a new `session/RollbackManager` collaborator and a pure `diff/FileRollback` (ordering helpers unit-tested) — `ClaudeSession` stays a thin delegating orchestrator. (`ui/DiffHistoryPanel.kt`, `ui/ClaudeToolWindowFactory.kt`, `session/RollbackManager.kt`, `diff/FileRollback.kt`, `ui/ChatMessageViews.kt`, `ui/TranscriptView.kt`, `ui/ChatPanel.kt`.)

### Changed
- **Minimum IDE is now 2025.1 (build 251)** — `since-build` moves up from 243. The composer attach menu uses the fluent `FileChooserDescriptorFactory.multiFiles()` / `singleDir()` descriptors introduced in 2025.1; they don't exist on 2024.3, where the old build would `NoSuchMethodError`. 2024.3 users stay on the last compatible release. (`build.gradle.kts`.)
- **Attachment mentions are cwd-relative on the wire** — a file attachment is sent to the binary as an `@<cwd-relative>` mention it actually expands (an absolute `@/…` path wasn't recognized), while the user bubble shows a **clickable `jb://open` link** to the file (wire text and display text are now built separately). (`session/ClaudeSession.kt`.)
- **More file references become links** — the markdown linkifier now links bare file paths **without** a line number too: permissive inside code spans (a `src/Foo.kt` in backticks links at line 1), conservative in prose (only an obvious path with a `/`, or an explicit `path:line`, so a product name like "Node.js" isn't turned into a dead link). (`ui/MarkdownRenderer.kt`.)
- **Compact attachment chips** — smaller chips with a small self-painted ✕ (replacing the chunky stock close button) and a down-scaled file-type glyph. (`ui/AttachmentStripPanel.kt`.)
- **Settings page no longer sprawls** — the page is pinned to a fixed content width on the left and its HTML security notes wrap, so on a wide (2K+) monitor the form and the tool-checkbox grids stop stretching edge-to-edge. (`ui/ClaudeSettingsConfigurable.kt`.)
- **Native `/login` — no IDE terminal** — signing in no longer drops you into a terminal tab (which broke once the **Reworked terminal** became the default engine in 2025.2: the legacy `createShellWidget` factory creates a deprecated *Classic* tab whose command-send races shell startup, so `claude auth login` was dropped). `/login` now spawns `claude auth login` under a real PTY (**pty4j**, bundled in the platform), lets the binary drive its own OAuth flow, opens the authorize URL in the IDE browser, collects the code from the callback page via a native input dialog, writes it back to the PTY, and restarts the session on success. The pure output parser (URL / "paste code" prompt / result extraction, layout-agnostic to the Ink TUI's cursor positioning) is unit-tested. (`process/ClaudeLoginFlow.kt`, `process/LoginOutputParser.kt`, `session/ClaudeSession.kt`, `ui/ChatPanel.kt`.)

### Fixed
- **IDE terminal launch on 2025.2+ (Reworked terminal default)** — the terminal helper, now only the fallback for the native login flow above, drives the **Reworked Terminal API** (`TerminalToolWindowTabsManager` + `TerminalView…shouldExecute().send()`, available 2025.3+) on modern IDEs and only falls back to the deprecated Classic `createShellWidget` path below 253 — all reflectively, so the verifier sees no deprecated/experimental API. (`process/TerminalLauncher.kt`.)
- **Rollback of a file-creating Write deletes the file** instead of leaving a 0-byte husk — the snapshot now records whether the file existed before, so reverting a creation removes it while reverting an overwrite restores the prior contents. Revert (per-row and Roll-back-all) also surfaces a success/failure **notification** so a click is never a silent no-op. (`diff/EditSnapshotStore.kt`, `diff/FileRollback.kt`, `session/ClaudeSession.kt`.)
- **Ctrl+O no longer jumps the scroll** — collapsing the reasoning blocks used to leave the viewport pointing at shifted content; the view now re-pins after the relayout (and `scrollToBottom` validates the layout before reading the extent). (`ui/TranscriptView.kt`.)
- The in-card **Diff/Revert action buttons** on tool rows get a comfortable padded hit area + hover highlight and are spaced apart, so they're no longer tiny adjacent targets. (`ui/ChatMessageViews.kt`.)

## [3.0.1] — 2026-06-03

### Added
- **Log in from the IDE**: `/login` (and any auth-failure result / `auth_status` error) can't run inside the TTY-less stream-json session — the binary answers *"not available on this environment"*. The plugin now detects this and offers a **"Log in in terminal"** notification that opens a native IDE terminal running `claude auth login`, **always launched with the binary's absolute path** so a GUI IDE that didn't inherit the user's login `$PATH` still finds it. Typing `/login` in the composer is intercepted client-side and routed to the same flow, and `/login` is now listed in the command palette (the binary never advertised it over stream-json). On **Windows** the command is prefixed with PowerShell's call operator (`& "…\claude.exe" auth login`) so the quoted path executes instead of being echoed. Uses the bundled Terminal plugin (runtime access via an optional `<depends>`), guarded so a disabled Terminal plugin degrades to a notice carrying the exact command. (`process/TerminalLauncher.kt`, `session/LoginDetection.kt`, `session/ClaudeSession.kt`, `ui/ChatPanel.kt`, `ui/CommandPalette.kt`.)

### Performance (no behaviour change)
- **Streaming delta coalescing**: buffer consecutive assistant/thinking deltas (and the live token usage) on the reader thread and flush them to the EDT in a single `invokeLater` per batch — flushed before every non-delta event / boundary / finalize / result / stop, so ordering is preserved and no delta is lost. Drastically fewer EDT hops/repaints during streaming. (`session/ClaudeSession.kt`; the shared buffer is lock-guarded so an EDT-side restart can't race the reader thread.)
- **Per-row transcript render**: `TranscriptView.flushDirty` revalidates/repaints only the changed rows and falls back to a full layout only when a row's preferred height actually changes (`ui/TranscriptView.kt`).
- **Markdown memoisation + highlighter cache**: skip re-render when the text is unchanged; cache the `SyntaxHighlighter` per language; don't highlight unterminated code fences (`ui/ChatMessageViews.kt`).
- **O(1) tool-output anchoring**: use the existing `byToolUseId` map instead of an `indexOfLast` predicate scan (`session/TranscriptModel.kt`).
- **Single shared pulse timer** for all tool boxes instead of one `Timer` per box (`ui/ChatMessageViews.kt`/`ui/TranscriptView.kt`).
- **Session-scoped quota poll**: one poll per session instead of one per open tab (`session/ClaudeSession.kt`/`ui/ChatPanel.kt`).
- **Lazy transcript restore**: reconstruct the last N entries (`DEFAULT_RESTORE_CAP`) on restore/fork/open-previous; full context still resumed by the binary (`session/SessionTranscriptReader.kt`).
- **O(n) stdout line splitter**: read-offset scan + single compaction instead of per-line `delete` (`process/ClaudeProcess.kt`).

### Fixed (post-review hardening)
- **Unbounded stdout buffer**: cap a newline-free stream at 16 MiB (drop + warn) so a malformed/stuck binary stream can't grow memory without bound (`process/ClaudeProcess.kt`).
- **Streaming auto-follow**: when pinned to the bottom, always re-pin after a flush instead of only when a row's height changed — a stale `preferredSize` no longer stops the transcript from following the stream or clips the newest line (`ui/TranscriptView.kt`).
- **Transcript restore cap**: drop orphan tool-result rows anywhere in the kept window (not just leading ones), so a restored session never shows a result without its call (`session/SessionTranscriptReader.kt`).
- **Usage meter**: a quota poll while the process is stopped no longer overwrites the last good cost/context with null (`session/ClaudeSession.kt`).
- **Delta drain on teardown**: `flushDeltas` applies synchronously when already on the EDT (stop/dispose), so final streamed text isn't lost to an unrun `invokeLater` (`session/ClaudeSession.kt`).
- **Markdown memo**: bust the cached HTML on IDE theme change so code-block colours follow a LAF switch (`ui/ChatMessageViews.kt`).
- Defensive handling + tests for duplicate `tool_use_id`; removed a redundant render pair in the composer.
- **Toggling thinking on/off no longer kills the session**: switching extended thinking (or the model) restarts the process via `--resume`, and the *old* process's late `onTerminated` could arrive after the new one was up and tear it down — Claude Code "disappeared" / didn't come back. A per-launch generation counter now ignores stale termination callbacks, so the restart resumes cleanly (`session/ClaudeSession.kt`).
- **Diff shows in every permission mode**: the pre-write snapshot is now captured on the `tool_use` event (before the binary writes), not only at `can_use_tool` approval — so the inline diff + "View diff" appear in acceptEdits / bypass / auto / dont-ask too, where the binary auto-executes without asking. First-capture-wins so a later re-capture can't overwrite the before-text (`session/ClaudeSession.kt`, `diff/EditSnapshotStore.kt`).
- **`/login` no longer dead-ends**: in the stream-json session the binary has no TTY, so `/login` answered *"not available on this environment"* and the user was stuck. It's now intercepted and routed to an interactive IDE terminal (see *Added*), and a detected auth failure surfaces the same actionable prompt instead of just an error line.

### Notes
- A performance pass plus a post-review hardening pass (thread-safety, behaviour-equivalence, security), and one new user-facing capability (IDE-terminal login). Tests added for the tool index, stream-event parsing, transcript cap (incl. mid-window orphans), delta coalescing, duplicate tool ids, the login detector, and the terminal login command. 503 tests, 0 failures.

## [3.0.0] — 2026-06-03

### Added
- **Full SDK protocol surface**: all `system/*` and stream events parsed (E1) and every host→binary control request wired (E2) — `get_settings`, `get_binary_version`, `mcp_reconnect`/`mcp_toggle`, `stop_task`, `rename_session`, and more.
- **Graphical session consumption** (E7): `SessionUsagePanel` paints context window + honest session-output tokens + a unified quota bar (utilization %, reset countdown **and** absolute reset hour), replacing the old loose quota labels and the inline token suffix.
- **Rich IDE attachments** (E8): `Attachment`/`AttachmentEncoder`/`AttachmentStripPanel`/`AttachmentActions` — pin files/selections as chips (editor actions + 📎 button) and drag&drop/paste images as native base64 content blocks via `ControlProtocol.userMessageWithImages`; size-guarded and read off the EDT.
- **Subagent live strip** (E10): `SubagentTasksPanel` — one card per in-flight Task subagent (tokens/tool-uses/elapsed + Stop), with status/error surfaced; `TaskTracker.onUpdated` merges status/error patches and clears on stop/restart.
- **Advanced launch options** (E6): max turns, max budget (USD), fallback model, `--add-dir` roots, `--betas`, strict MCP config — in Settings and threaded through `SessionLauncher`.
- **Plan mode + richer permissions** (E4): ExitPlanMode plan cards, decision reasons, blocked-path context.
- **Session management** (E5): rename / fork / delete past sessions (binary session files remain the source of truth; `SessionStore.delete` is UUID-guarded).
- **Native hooks** (E3): `hook_callback` answered host-side by the pure `HookBroker` (decision + IDE side effects); the real tool gate stays `can_use_tool`.
- **Account & diagnostics** (E11/E2-UI): Account, Binary Version, Effective Settings, and an interactive MCP-runtime dialog in the gear menu.
- **Diff button** on every Edit/Write/MultiEdit row + syntax-highlighted code (E9).
- **Tool-call lifecycle on the box**: each tool card reflects its state by border colour — **sky-blue while in flight**, **pulsing sky↔amber** for a sense of motion while it works, **green when finished**. The elapsed time is shown while running (the protocol carries no completion %, so time is surfaced instead of a fake progress bar).

### Changed
- **Architecture refactor**: `ClaudeSession` and `ChatPanel` decomposed into single-responsibility collaborators (TokenAccountant, TaskTracker, TranscriptReconciler, DiffLifecycleManager, SessionControlClient, PermissionCardManager, HookBroker, SessionLauncher; UI sub-panels) — the orchestrators are now thin, enabling parallel epic work.
- **Native UI pass**: the consumption readout is rebuilt from native components (`JProgressBar` + labels) and shows the token breakdown (in/cache/out) **inline**; the "thinking" indicator uses the IDE-native `AsyncProcessIcon`; the Settings page is wrapped in a scroll pane (responsive). The 📎 attach button opens a **selector menu** (current file / selection / clipboard image) instead of attaching the open file directly.
- **Authoritative usage**: session tokens come from the binary's cumulative `get_session_cost.apiUsage` (and context from `get_context_usage`) rather than a drifting local fold; quota shows only when the binary reports `utilization` (no misleading 0%), retaining the last known value.
- Composer model/mode chips clarified: model default → "Default · Opus 4.8 (recommended)"; permission-mode menus/combo show human labels ("Ask each time", "Accept edits", …). Shared `TokenFormat`.

### Fixed
- Final hardening pass: image read/encode moved off the EDT (with a pre-read size guard); absolute reset hour + non-colour warning marker restored (WCAG 1.4.1); subagent status/error updates wired; duplicate `formatTokens` divergence removed.
- Approving a plan (ExitPlanMode) now flips the plugin's permission mode back to default — the Mode chip no longer stays stuck on "plan".

### Notes
- **474 tests** in the default `test` task (0 failures, 2 Windows-only skips), plus the gated RemoteRobot UI suite (locators validated against a live IDE). Compatible with IU-261 and IU-262 (RC), zero deprecated/internal APIs.

## [2.2.2] — 2026-06-03

### Added
- **Headless component tests** (`src/test/.../headless/`, IntelliJ Platform `BasePlatformTestCase`, run in-process): `OpenedDiffsService`, `ChatSessionManager`, `SessionHistory` service round-trip, `ClaudeSettings` service (defaults + always-allow), `ClaudeSettingsConfigurable` (combo fallbacks + apply/reset/dispose), and real **token-accounting** verification (all four usage components fold into the session total across messages).
- **Integration tests** (`src/test/.../integration/`) driving a real `ClaudeSession` against `bin/fake-claude` with JSONL fixtures: init/streaming, thinking turn, token accounting, multi-message token fold, rate-limit, tool-use permission resolution, resume reconstruction, interrupt, and the "Write-unsafe context" regression path.
- **End-to-end UI tests** (`src/uiTest/`, RemoteRobot, gated by `-PuiTest.enabled=true`): chat smoke, View diff, Close All Diffs, jump-to-code, thinking toggle, keyboard shortcuts, Open Previous Session, Settings model combo, notifications — ready to run in the nightly UI workflow.
- **Release automation**: `.github/workflows/release.yml` (tag-triggered: full test + verifyPlugin gate, then sign + publish to Marketplace, plus a GitHub Release) and `.github/workflows/ui-tests.yml` (nightly RemoteRobot under Xvfb). `docs/BRANCHING.md` documents the GitFlow + branch-protection conventions.
- `ClaudeSession.handleEventForTest(event)` — a `@TestOnly` seam so headless tests can drive event reconciliation without spawning the binary.

### Notes
- Same runtime behaviour as 2.2.0/2.2.1 for end users; this release completes the automated test pyramid (unit → headless → integration → UI) and the maintenance/release workflow. Test count: **239** in the default `test` task (0 failures, 2 Windows-only skips), plus the gated UI suite.

## [2.2.1] — 2026-06-03

### Added
- **Maintenance baseline**: `SECURITY.md` (responsible disclosure policy + SLAs), `CONTRIBUTING.md` (dev workflow), `CODEOWNERS`, GitHub issue & PR templates, and Dependabot config for Gradle + the SDK reference (`@anthropic-ai/claude-agent-sdk`).
- **CI**: `.github/workflows/ci.yml` runs `./gradlew test verifyPlugin buildPlugin` on every push/PR with JDK 21 + Gradle cache and uploads the plugin zip as an artifact.
- **Drift detection**: `.github/workflows/sdk-drift.yml` (weekly) opens an issue when a newer SDK is published; `.github/workflows/binary-drift.yml` (daily) when a newer `claude` binary is released; `.github/workflows/binary-probe.yml` (weekly + manual) runs the real binary against canonical inputs and opens an issue if it emits an event type the plugin doesn't parse.
- **Documentation**: `docs/RELEASE_PROCEDURE.md`, `docs/RELEASE_CHECKLIST.md`, `docs/BINARY_COMPAT.md`, `docs/FAQ.md`, `docs/TROUBLESHOOTING.md`, `docs/TELEMETRY.md` — a real release/maintenance workflow for an in-Marketplace plugin.
- **Test pyramid foundations**: new Gradle source sets `integrationTest` and `uiTest` (`./gradlew integrationTest` runs against a deterministic `bin/fake-claude` Python stand-in fed JSONL fixtures from `src/integrationTest/resources/fixtures/`; `uiTest` reserved for the Sprint 3 RemoteRobot end-to-end suite, gated by `-PuiTest.enabled=true`).
- **Coverage**: `kotlinx-kover` integrated; `./gradlew koverHtmlReport` produces a coverage report.
- **Layer A unit tests** (67 new, total **202 / 0 fail / 2 skipped on non-Windows**): `DiffPresenter.isWithinRoot` direct (incl. symlink escape attempts), exhaustive `PermissionBroker` matrix (mode × tool × within-root × remembered), `ClaudeBinaryLocator` (incl. Windows `.cmd` shim regression resolved with `Assumptions.assumeTrue`), `McpConfigBuilder` (SSE / streamable-http / stdio + custom server merging + invalid JSON tolerance), `Protocol.parseAskQuestions`, and `MarkdownRenderer` edge combinations (table cells with code/links, unterminated fences, nested task lists, contiguous autolink + `path:line`).
- `bin/fake-claude` Python stand-in plus the `init_basic.jsonl` fixture: handles the initialize handshake, replays a streamed text turn with `message_start` / `content_block_delta` / `message_delta` / `result`, and emits per-message usage with all four token components so integration tests can pin token-accounting behaviour without hitting the real model.

### Changed
- README install path now points to the JetBrains Marketplace as the canonical source (GitHub remains the source of truth for code).

### Notes
- 2.2.1 has the same runtime behaviour as 2.2.0 — this release is the infrastructure update (tests + workflows + docs) so the plugin can be maintained seriously with real users on the Marketplace.

## [2.2.0] — 2026-05-28

### Added
- **Model picker reflects what the binary actually returns** — the Settings combo now lists the binary's modern aliases (`default` = Opus 4.8 with 1M context, `sonnet` = Sonnet 4.6, `haiku` = Haiku 4.5) and updates **live** as soon as the `initialize` handshake lands, showing each as its `displayName` ("Default (recommended)", "Sonnet", "Haiku") instead of the raw wire value. The historical Opus 4.7/4.5/Opusplan tags stay as fallback for back-compat.
- **Path:line links inside code spans** — `` `src/Foo.kt:42` `` (the natural way the model writes references) now renders as a clickable `jb://open` link wrapped in `<code>` instead of inert monospace text. Project-confinement (`DiffPresenter.isWithinRoot`) still gates the click.
- Protocol surface bumped to SDK 0.3.161 / binary 2.1.161: `ModelInfo` carries `supportsEffort` / `supportedEffortLevels` / `supportsAdaptiveThinking` / `supportsFastMode` / `supportsAutoMode`; `AccountInfo` carries `apiProvider` / `apiKeySource`. Extra `system/*` events from the new binary (`task_progress`, `task_notification`, `background_task_*`, `auth_status`, `session_state_changed`) are tolerated by the lenient codec — UI surfacing to come.
- Tests: `MarkdownRenderer` linkify-inside-code-span (134 total).

### Changed
- **Default model is now `default`** (the binary's recommended-tier alias), not the hard-coded `claude-opus-4-7`. Fresh installs follow the binary's recommendation (currently Opus 4.8); existing settings keep their persisted model value untouched.

### Fixed
- **Marketplace publishing**: migrated the bundled MCP plugin lookup from the internal `PluginManager.getInstance().findEnabledPlugin(PluginId)` to the public static `PluginManager.getPlugin(PluginId)?.takeIf { it.isEnabled }`. This was the lone internal-API hit that blocked the 2.0.1 upload re-check; verified with `javap` against the platform jars.

## [2.1.0] — 2026-05-27

### Added
- **Persistent diff from the transcript** — Edit/Write/MultiEdit tool cards carry a "View diff" button that re-opens the old↔new diff at any time, in any permission mode. A new `EditSnapshotStore` captures the pre-write file contents at approval time, keyed by `tool_use_id`.
- **Hunk-by-hunk acceptance** — the permission card lists the change's hunks (via the platform diff `ComparisonManager`) with checkboxes; accepting a subset sends a narrowed `updatedInput` so the binary writes only the selected hunks. `file_path` is never modified.
- **AskUserQuestion options wrap** — labels, descriptions and the per-option `preview` (previously unused) render in full instead of clipping to one line.
- **"Explain with Claude"** editor-popup action sends the current selection (with file path) to the active session.
- **Jump-to-code** — `path:line` references in replies become `jb://open` links that navigate to the file/line in the IDE.
- **"Always allow" per tool** — persisted in `ClaudeSettings`; remembered tools auto-approve while reviewable writes stay confined to the project root. Settings ▸ Claude Code now lists the remembered tools with a Remove action, so the rule can be revoked without editing XML.
- **Session attention notifications + tab badge** — a background session with a pending permission, a finished turn, or an error raises a notification and badges its tab; suppressed when that tab is the one on screen.
- **Session history (reads the binary's own files)** — past conversations are read back from the `claude` binary's session transcripts (`~/.claude/projects/.../<sessionId>.jsonl`), the single source of truth. "Open Previous Session…" lists the project's sessions by their real title (as `--resume` shows them) and re-attaches via `--resume`. The plugin persists **no transcripts** — only the open-tab session ids, in `workspace.xml` (not committed by convention).
- **Restore on startup** — the tabs you had open are reopened automatically; if none were recorded, the most recent session is restored. Toggle: Settings ▸ Claude Code ▸ "Restore open chats on startup".
- Markdown rendering: strikethrough (`~~`), GFM task-list checkboxes, nested lists.
- Tests: `EditSnapshotStore`, `PermissionBroker` tool_use_id plumbing, `HunkSelection`, `MarkdownRenderer`, `SessionHistory` open-tab ids, `SessionStore` path-traversal guard + cwd encoding, `SessionTitleReader`/`SessionTranscriptReader` JSONL parsing, and the settings enums (132 total).

### Changed
- Permission mode, effort and MCP transport are now backed by typed enums (`PermissionMode`/`EffortLevel`/`McpTransport`) as the single source of truth for allowed values and branching; the persisted/wire strings are unchanged (no config migration).

### Security
- Jump-to-code navigation is confined to the project root (`DiffPresenter.isWithinRoot`): a crafted `jb://open` link cannot open absolute paths, `~/.ssh`, `/etc`, or `..`-traversed files.
- Explicit Markdown links are restricted to an allow-list of schemes (`http`/`https`/`jb`) with the href quote-escaped; other schemes (`javascript:`, `file:`, `data:`, relative) render as plain text.
- No conversation content is written to project files anymore: session history keeps only open-tab ids in `workspace.xml`. Session-file reads are confined to `~/.claude/projects` and gated by a UUID-shaped id check (`SessionStore`), so a crafted session id can't traverse out of the tree.

### Fixed
- Markdown: a bare URL inside an explicit link's href is no longer double-linkified (`<a href="<a href=…">`).
- Notifications no longer pop for the chat already on screen (the over-strict tool-window `isActive` check is gone; visible+selected tab is enough to suppress), and the notification's **Open** action now dismisses it.
- Fixed a "Write-unsafe context!" crash when refreshing files the agent edited: the VFS refresh is now asynchronous (`refreshIoFiles`), which is safe from the non-write-safe modality it runs under.
- **Extended thinking shows again** on current models (Opus 4.7 / `claude` 2.1.152+): reasoning is now enabled via the launch flags `--thinking adaptive --thinking-display summarized` instead of the deprecated `set_max_thinking_tokens` control, which no longer surfaces "Thought process" blocks. Thinking is now on/off (adaptive — the model decides depth); toggling the chip restarts the session via `--resume`.

## [2.0.1] — 2026-05-27

### Changed
- Extended the supported IDE range to the current EAP (`until-build` = `262.*`); verified Compatible against IU-262.
- Replaced the internal `PluginManagerCore` lookup for the bundled MCP Server plugin with the public `PluginManager` by-id API, removing the last internal-API usage.

## [2.0.0] — 2026-05-26

### Security
- Auto-approved file writes in `acceptEdits` / `bypassPermissions` are confined to the project root: a write whose canonical path (symlinks resolved) falls outside the project degrades to a manual Accept/Reject card instead of being written silently.
- Trust-on-open gate: when a project-level `claude-code.xml` carries a source script or a custom stdio MCP server — both of which execute code at launch — the plugin prompts for confirmation once before running them (declining aborts the launch).
- The source script is invoked with its path as a positional shell argument instead of being interpolated into the command string, removing a shell-injection vector via a crafted path.
- Settings now warn that environment variables are stored in plain text in `claude-code.xml` and that the source script is executed on session start.

### Fixed
- EDT freeze on session start: environment resolution (sources a login shell, multi-second timeout) and process spawn now run on a pooled thread; the resolved environment is cached per session. Opening the first chat or sending the first prompt no longer hangs the IDE.
- In-flight control requests are now completed (with failure) on `stop()` / process termination / dispose, fixing dialogs stuck on "Loading…" and leaked callbacks.
- Control requests now have a 30s watchdog; a hung binary no longer leaves the callback pending indefinitely.
- Process start failures are surfaced via notification (not just the transcript) and no longer leave a half-initialized "ready" session; `writeLine` logs (and reports) lines dropped to a dead stdin instead of discarding them silently.

### Added
- First unit-test suite (80 tests): `ProtocolParser`, `ControlProtocol`, `DiffPresenter` reconstruction, `TranscriptModel` hierarchy, `RateLimitInfo` math, and environment parsing (`EnvScriptLoader.parse`, `ClaudeSettings.parseEnv`).

### Changed
- MCP config building extracted to a standalone, testable `McpConfigBuilder` (identical wire output).
- Thread-safe tab counter (`AtomicInteger`); named constants for UI timings/quota thresholds; debug logging on previously silent decode/parse failures.

## [1.3.5] — 2026-05-26

### Added
- **IDE tools over MCP (opt-in).** Two independent controls in Settings ▸ Claude Code:
  - *Enable JetBrains MCP server* — wires JetBrains' own MCP Server plugin via `--mcp-config`. Pick the transport (`sse`, `streamable-http`, or `stdio`) and port; for `sse`/`streamable-http` the default localhost endpoint is synthesized (no JSON to type), and `stdio` is built automatically from the running IDE's paths (JBR `java` + the bundled `mcpserver` libs), so it works on Windows unchanged.
  - *Custom MCP servers* — add any number of your own servers as a JSON object (`name → server config`), merged alongside the JetBrains one.
- Off by default; tool calls remain gated by the in-chat permission prompt. Invalid custom JSON is rejected on save.

## [1.3.1] — 2026-05-26

### Fixed
- Settings: the model dropdown was empty when opened before the binary's initialize handshake — it now always lists the available models plus known fallbacks (shared with the gear menu).
- Settings: removed the blank entry in the Effort dropdown.

### Changed
- Default model is now **Opus 4.7** (`claude-opus-4-7`).
- Default effort is now **medium**.

## [1.3.0] — 2026-05-26

### Added
- Windows support: the `claude` binary is detected on Windows (`claude.exe` / `claude.cmd`) across npm, scoop, volta, chocolatey and `~\.local\bin`. npm `.cmd` shims are driven as `node cli.js` directly, bypassing cmd.exe (which corrupted the streaming stdio pipe and mangled argument quoting).
- Settings: explicit overrides for the `claude` and `node` executable paths — the catch-all for non-standard installs, version managers, or a GUI IDE that doesn't inherit the user's PATH.
- Settings: configurable environment variables (`KEY=VALUE` per line), injected into the binary's process — useful on Windows for `PATH` additions.
- Settings: **Source script** — point to a `.sh` (sourced in the login shell on Linux/macOS) or a PowerShell profile/`.ps1` (dot-sourced on Windows); the resulting environment is captured and applied to the `claude` process, so the IDE inherits the same `PATH`/setup as the user's own shell.
- "Binary not found" notification now carries a **Configure paths…** action that opens the settings page directly.

### Changed
- Auto-detected `claude` path is persisted to settings on first successful launch (and refreshed if a saved path goes stale), so launches are stable and the path is visible/editable.

## [1.2.0] — 2026-05-26

### Added
- Tool output is now shown in the chat as a code block immediately below the tool call card. Outputs longer than 200 lines are truncated with an indicator. Supports all tools (Bash, Read, Edit, Grep, Glob, WebFetch, etc.)
- Tool calls are now collapsible groups: a disclosure triangle on each tool card shows/hides its output. Applies to every tool that produces output.
- Subagent (`Task`/Agent) activity nests under its Agent: the subagent's tool calls, outputs and text are anchored and indented beneath the Agent card, and collapse hierarchically (collapsing the Agent hides its whole subtree; collapsing a sub-tool hides only its output).

### Changed
- Info bar above the composer reordered: (1) Resets in countdown, (2) Reset Hour, (3) Session Usage %, (4) Brewing / live tokens / Esc to interrupt

### Fixed
- Tool outputs now anchor directly under their tool call instead of drifting to the end of the transcript — including tools that require human interaction (permission cards, `AskUserQuestion`) and long-running calls. Parallel tool calls keep each output under its own call.
- Replaced all deprecated `JBUI.scale()` calls with `JBUIScale.scale()` across the UI (`ChatPanel`, `TranscriptView`, `ChatMessageViews`, `CommandPalette`, `ClaudeSettingsConfigurable`, `ChatTheme`)

## [1.1.0] — 2026-05-26

### Fixed
- Quota bar stays visible with reset countdown when utilization % is not reported (Max plans); % meter hides independently
- `isWarning` / `isExhausted` no longer fire on `overageStatus = "rejected"` alone
- Token counter now accumulates correctly across multi-message turns (tool calls, chained assistant messages)
- Failed turns with no `result` text (`error_*` subtypes) surface the `errors` list or subtype name — no more silent failures
- `dispose()` sends EOF before killing the process (clean exit, same order as `stop()`)
- `LiveUsage` updates moved to EDT to eliminate read-modify-write race on token counters
- `ready` and `process` marked `@Volatile` — visibility gap on session start/stop across threads
- Startup queue flushed after `system/init` — messages sent before the handshake are no longer dropped
- `JBUI.scale` → `JBUIScale.scale` for correct stroke scaling on IntelliJ Platform 2025+

### Added
- `errors: List<String>` field on `ResultMessage` to capture SDK `SDKResultError.errors` payloads

## [1.0.0] — 2026-05-26

### Added
- Native stream-json + control protocol transport (one long-lived process per tab)
- Streaming chat transcript with markdown rendering (bold, code blocks, tables)
- Multi-chat tabs via `ChatSessionManager`
- Permission-gated diff review: Edit/Write proposals shown as in-editor diff tab + inline Accept/Reject card
- `AskUserQuestion` support with multi-select option cards
- Slash-command palette (all commands from `initialize` + client-side `/btw`)
- Model / effort / permission-mode / thinking chips + gear menu
- Multi-prompt queue (send follow-ups while agent works)
- Quota bar + live token counter + reset countdown
- Auto-diff on acceptEdits / bypass permission mode
- Ctrl+O toggle for reasoning blocks
- Status bar with thinking indicator, live token count and "Esc to interrupt"
- Settings: model, permission mode, effort, thinking tokens, allowed/disallowed tools, setting sources, output style
- UI rethemed to follow the active IDE theme (light/dark); Claude logo icon

[2.1.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v2.0.1...v2.1.0
[2.0.1]: https://github.com/lain/claude-code-for-jetbrains/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.5...v2.0.0
[1.3.5]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.1...v1.3.5
[1.3.1]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lain/claude-code-for-jetbrains/releases/tag/v1.0.0
