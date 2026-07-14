## v4.3.1 — 2026-07-14

## 🛡 Deterministic sensitive-data protection

A new permission-layer control evaluates **every** tool call before it can be auto-approved. It is deterministic and enforced outside the model: the classification and verdict are the plugin's, independent of anything the model or a prompt injection can say.

**What it covers.** Calls that touch credential or key material — SSH/GPG keys, cloud and cluster credentials, database and shell-history secrets, browser and password-manager stores, crypto wallets, and the access tokens of well-known AI agents and code hosts. Patterns match by structure, so the same rule covers Linux, macOS, Windows (`C:\Users\…\.ssh`) and WSL (`/mnt/c/Users/…\.ssh`). Credential-dumping and exfiltration commands (secret exports, reverse shells, offensive tooling) are covered too, evaluated after resolving symlinks and `..` on disk and after normalising common shell obfuscation (broken quotes, `$IFS`, a path hidden in a variable, a base64 payload piped to a shell).

**How it decides.** The agent's own tools require an explicit permission card whenever a call is flagged — **including in `acceptEdits` and `bypassPermissions`**. MCP servers and Skills are denied access to that material rather than prompted. Access that reaches another user's home, a network or UNC mount, or a foreign WSL drive is denied for every caller. The blacklist is configurable additively (you can widen it, not narrow it), and a session will not start when the project is located on a remote or network-mounted drive.

**Scope.** Detecting a path concealed inside an arbitrary shell string is best-effort and can be widened over time; the enforcement of a match, however, is absolute and cannot be overridden by the model. See `SECURITY.md` for the full model.

## 🔗 Jump to code, straight from the conversation

**Claude names a file, you click it, you are there.** The conversation stops being a wall of text you have to translate back into your project.

**On tool cards.** A file tool now names its file **the way you think about it** — `Read(src/main/kotlin/permission/PermissionBroker.kt)`, relative to the project, not a bare `PermissionBroker.kt` that tells you nothing about *which* one. And it is a link: it opens the file in the editor **at the right line** and selects it in the Project view, so you can see where it lives.

**In Claude's own words.** Paths (`src/Foo.kt`, `a/b.py:42`, `~/.claude`), **directories** (`build/` — revealed and expanded in the Project view, or opened in your file manager when they live outside the project) and **symbols** (`PermissionBroker` → straight to its declaration) all become links. Even the way developers actually cite a file works: **`app.css:190`**, a bare name and a line, resolves through the IDE's file index — and through a bounded on-disk scan for *excluded* folders like `build/`, which no index knows about. Archives reveal in the tree instead of opening a useless binary buffer.

**And it never lies to you.** The transcript can only *guess* what is a path or a symbol — so nothing is linked on a guess: the IDE confirms every candidate first, and links only what it can resolve **unambiguously**. Two files named `app.css`? No link at all, rather than a jump to an arbitrary one. A path that does not exist stays plain text. A link is never dead, and never takes you somewhere you did not ask for. Symbols resolve through *Go to Symbol*, so this works in **every** JetBrains IDE, not just the Java/Kotlin ones — and a link can only ever point inside your project or your own home, never at `/etc/passwd`, never at another user's files, not even through a symlink.

### Also in this release

**💾 The IDE sees Claude's writes immediately.** The virtual file system was only refreshed at the *end* of a turn, so until Claude went idle the editor showed stale contents — and a link to a file Claude had just written opened nothing at all, because the IDE did not know that file existed yet. Every successful write now refreshes at once: by exact path for `Edit`/`Write`, and by re-scanning the project tree after a `Bash` command or a file-mutating MCP tool, which can change anything. Newly *created* files are picked up too (refreshing a file the VFS has never heard of is a no-op, so its parent directory is re-scanned as well).

**💬 Fixed: a chat tab could come up unusable — the composer refused to take focus.** A new tab (and sometimes the tabs restored at startup) gave you a chat you could not click into; the only cure was closing and reopening the tool window. And even when keystrokes did arrive, a fresh tab showed **no caret**. Both are gone: the tab now tells the platform where its keyboard focus lives and lets the IDE hand it over, and the web view is told it has the focus once the chat actually exists — which is when there is a caret to paint.

---

## v4.2.0 — 2026-07-08

**Protocol upgrade to `claude` 2.1.204 / SDK 0.3.204**, plus a new dashboard card.

**🗂 Background tasks in the session dashboard.** The plugin now understands the binary's `background_tasks_changed` signal and shows a **Background tasks** card (with **Stop**) listing everything running in the background. It's a *level* signal — the full live set is re-sent on every change — so unlike the Subagents list it can never get stuck showing work that already finished.

**🔁 Retry progress for `/btw`.** Progress for long-running side questions is now recognised: an API retry is surfaced as a "Retrying (attempt n/m)…" notice instead of being silently dropped.

**🧠 Fixed: empty "Thought process" on newer models.** Opus 4.8 emits **redacted** thinking — no reasoning text at all, only a signature. The plugin was opening an empty "Thought process" fold for it that never filled. Now there's simply no fold when there's no reasoning to show.

**🔌 Fixed: the MCP servers card layout.** The *Reconnect* button and the enable/disable switch overlapped each other, and the switch's knob painted on top of its own label. The actions row now lays out correctly, the switch is a real switch, and long server names ellipsize instead of pushing the buttons out of the row.

- Models the new `system/background_tasks_changed` and `system/control_request_progress` messages.
- Triages the new `list_models`, `get_plan` and `get_workspace_diff` control requests (thin-client only; the plugin reads its model catalog from the `initialize` reply).
- Backward-compatible with older binaries.

---

## v4.1.0 — 2026-06-27

This release folds in everything since v4.0.3 — the intermediate 4.0.4 / 4.0.5 builds were never published separately, so all of their fixes ship here.

**✨ Editable diff review for edits.** When Claude asks to Edit/Write/MultiEdit a file, the plugin now **auto-opens an editable diff** in the IDE editor (Current | Proposed) on the permission request — not just in acceptEdits/bypass mode. You can **tweak the proposed content right in the editor** before approving; **Accept writes your edited version** (the tool input is re-encoded so the binary writes exactly what you left), and the diff **closes automatically** on accept/reject. The transcript's inline diff and **"View diff"** reflect what was *actually* written (your edit), not Claude's original proposal. Fail-safe: if you change nothing — or the platform renders the proposed side read-only — Accept writes Claude's original proposal, never a wrong write.

**🩹 Read-only diff replaces per-line checkboxes on edit permission cards.** The old hunk-by-hunk partial-acceptance UI (a checkbox per changed region) rendered as a confusing checklist and let you apply an incoherent subset of an edit — a reliable way to produce broken code. Edits are now **atomic**: the card shows a colour-coded diff (red removed / green added) and you accept or reject the whole change.

**🛠 Broad bug-fix + UX pass.** Protocol re-baselined to `claude` 2.1.193 / SDK 0.3.193 (models the new `system/informational`, `model_refusal_no_fallback`, `worker_shutting_down` subtypes).

- **Interrupt** actually stops the turn now — Esc / Stop clears the turn on the binary's ack/timeout instead of looping "Interrupting…" forever; queued prompts and pending permission requests are flushed/denied.
- **Chat dead on first open** self-heals (the web app retries until the bridge exists; the host reloads the page if it doesn't come alive) — no more closing & reopening the tab.
- **User prompts render verbatim** (never as Markdown); the code-block **Copy** button works; duplicate/out-of-order **"Thought process"** fixed.
- **Menu flicker + de-selection** while streaming fixed; single ✓ in prompt menus; Esc on the find bar no longer interrupts; the find bar scrolls + Enter/Shift+Enter navigation.
- **Adaptive thinking on by default**; faster Vibe Mode; **responsive** composer / find bar / chips and truncated tab titles.
- **"Always allow"** resolves the exact card; permission re-push no longer wipes in-progress input; the session dashboard no longer covers the composer; clipboard paste runs off-EDT (no IDE freeze on a hung Wayland clipboard).
- Latent fixes: double-`claude`-spawn guard + mid-launch orphan prevention, `dispose()` generation bump, a malformed `can_use_tool` can't hang the turn, and a per-project tool window (no cross-project shared state).

---

## v4.0.3 — 2026-06-10

**Fix: clipboard paste in the chat composer on native-Wayland Linux (the real fix for 4.0.2's symptom).**

On IntelliJ running the native Wayland toolkit, the embedded browser's clipboard is isolated from the system clipboard, so `Ctrl+V` in the composer only pasted things copied *inside* the chat — never text or images from other apps. 4.0.2 added the right host-side reader (`wl-paste`) but it was never reached, because the real problem is *where the paste is triggered*, not how it's read. The composer now routes `Ctrl+V` through the host whenever the Wayland toolkit is active (the same path the **Attach → Image** button already used), so pasting text and images from any app works — including pasting back what a Copy button put on the clipboard. X11/XWayland, Windows, and macOS are unchanged.

---

## v4.0.2 — 2026-06-10

**Fix: text paste on native-Wayland Linux IDEs.**

On IntelliJ 2026.1+ running the native Wayland toolkit, AWT's clipboard comes up empty, so pasting **plain text** (`Ctrl+V`) into the composer did nothing — while image paste worked, because it already had a `wl-paste`/`xclip` fallback. Text paste now uses the same host-side fallback, reading a genuine `text/*` target. It's guarded so an image-only clipboard (e.g. a KDE screenshot) is never mis-pasted as raw bytes, and copied files (`text/uri-list`) / HTML markup are excluded. X11/XWayland, Windows, and macOS are unaffected.

---

## v4.0.1 — 2026-06-10

**Protocol upgrade to `claude` 2.1.170 / SDK 0.3.170.**

Keeps the plugin in lock-step with the latest Claude Code binary. The drift detector (`./gradlew checkDrift`) flagged four new protocol kinds in 2.1.170; all are reconciled and the protocol surface is verified green again.

- **Model refusal fallback is now visible.** When the primary model declines a turn (stop_reason `refusal`), the binary retries it once on a fallback model. The plugin now recognises this new `system/model_refusal_fallback` message and shows a transcript notice — e.g. *"The model declined to respond (cyber) → retried on claude-sonnet-4-6."* — instead of silently dropping it, so you always know when a retry happened and which model produced the answer.
- **Protocol surface re-baselined** to `claude` 2.1.170 / SDK 0.3.170, with the new `get_usage` / `register_repo_root` / `reload_skills` host→binary control requests triaged into the known surface.

No UI or behavioural changes beyond the above; backward-compatible with older binaries (the new fields are all optional).

---

## v4.0.0 — 2026-06-04

**Chat UI rebuilt on JCEF (embedded Chromium).**

The entire chat surface is now an embedded Chromium web view (JCEF) instead of Swing — an inlined web app (no CDN, no external resources), themeable to your IDE. Diffs stay native via the IDE's `DiffManager`; everything else got a new web front end. The old Swing chat UI (and its tests) was removed.

- **Modern streaming transcript.** Token-by-token rendering of sanitized model markdown (tables, lists), collapsible tool cards that show live elapsed time, fenced code blocks with a language label and a Copy button, and a Ctrl/Cmd+F find bar. Links never navigate the view; external `https` links open in your system browser.
- **Web composer.** Input with model · mode · effort · thinking · provider controls, a queued-prompt strip, a predicted-next-prompt ghost suggestion, a `/` command palette, and attachment chips — including image **drag-and-drop** and **paste** straight into the composer, plus a file picker.
- **Native permission / question / elicitation cards.** Permission prompts, `AskUserQuestion`, and MCP **elicitation** (URL flow gated to http/https, or a form built from the request schema) render as inline cards — no modal dialogs.
- **Session dashboard.** A toggle flips the transcript to a dashboard: context breakdown by category, usage & cost (in / out / cache, USD when the binary reports it), account (email / org / plan / provider), the active model, in-flight subagents (with Stop), and MCP server health (status + reconnect / enable-disable per server).
- **Hardened web view.** Served from an in-process, network-less origin with a full set of real security headers and a strict, **hash-pinned** CSP (every script and the stylesheet allowed only by `sha256`, no `unsafe-inline`/`unsafe-eval`, `connect-src 'none'`), so untrusted rendered content can never fetch, exfiltrate, or execute injected script.
- **Requires JetBrains 2025.2+ (build 252+)** with JCEF (bundled with the IDE's JBR).

**Post-rewrite UI/UX hardening (4.0.0).** A full QA pass over the new JCEF surface closed a stack of bugs and re-ported the Swing features that mattered — all in the frontend, **the Kotlin backend was not touched**:

- **Subagents nest properly.** A Task/Agent card now contains its subagents' tool activity; each nested card collapses/expands on its own (was a descendant-selector bug forcing them all open).
- **Native rewind as the default rollback.** "Restore" on an edit asks Claude Code to `rewind_files` to that turn (client-tagged message uuid + `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`); falls back to the IDE-side per-file revert behind a confirmation (with a remembered "don't ask again"). Per-edit "View diff" + the Diff History rollback tab return.
- **Clipboard paste on Wayland.** Ctrl+V and "Paste image" are read host-side (text via AWT, image via `wl-paste`/`xclip` resolved across common paths), so image paste works where JCEF's web clipboard comes up empty; text pastes without duplicating.
- **Tool-card states** fade sky-blue↔amber while active, green on success, **red on error** (`is_error`). Inline edit diffs render colourised (added/removed/hunk).
- **Composer** is a flat single-row control bar (📎 · provider · model · mode · effort · thinking | 🌈 · history · follow · send) with the previous icon set; a session-usage line (running/idle + context + tokens) sits above it.
- **Restored/added:** Ctrl+O reasoning toggle (folds collapsed by default, with a hint), auto-follow scroll, the 🌈 Vibe Mode gag (Nyan Cat + rainbow), diffs open without stealing keyboard focus, request cards cap at half height with the body scrollable and actions always visible, `/login` runs in the IDE terminal (browser auto-capture) and shows in the palette, "Explain with Claude" carries the Claude icon, and a **Cancel** button on question cards.
- **Fixes:** the build didn't compile (`object a ChatTheme` + a nested-comment KDoc); session cost counters and the JetBrains MCP server now read the binary's `mcpServers` (camelCase) reply; ⚙ menu reuses the formatted dashboard instead of plain-text dialogs.

**Feature parity + web-only differentiators (4.0.0).** A second pass closed the remaining Swing gap and added what only the web view enables — still all frontend (backend wiring only reuses what already existed):

- **Hunk-by-hunk partial diff acceptance.** Reviewable Edit/Write/MultiEdit permission cards show a checkbox per changed region; accepting a subset narrows the input (`HunkSelection.encodeInput`) so the binary writes only the chosen hunks.
- **`jb://` jump-to-code links.** `@file` mentions render as clickable links that open the file at the line in the editor — DOMPurify-allowed and gated to the project root (`DiffPresenter.isWithinRoot`).
- **Rich attach menu (📎).** A search box + Files / Directory / Image + current selection/file + a filterable **Recent files** list (icon + name), AI-Assistant-style — recents from `FilePickerHelper.recentFiles`.
- **Syntax highlighting in the IDE's colours.** highlight.js token classes map to the live editor scheme (`DefaultLanguageHighlighterColors`), so code blocks match the IDE in any theme.
- **Inline images** (`data:` URIs, kept in-bounds) and a **responsive** layout for narrow tool windows.
- **Deliberately NOT added:** Mermaid / KaTeX — too much external bloat and they'd force relaxing the strict hash-pinned CSP. Kept the plugin lean (~1.6 MB) and the CSP intact.

**Expert-consensus review hardening (4.0.0).** A multi-reviewer pass over the parity changes confirmed and closed a handful of real defects — still frontend + thin UI wiring, the protocol backend untouched:

- **Partial accept never writes from a stale snapshot.** Accepting a subset of hunks now re-reads the file from disk first; if it changed since the card appeared, the plugin does a normal full accept instead of reconstructing from the cached snapshot (which could silently no-op or overwrite an external edit).
- **No more `hunkCache` growth.** Cached hunk contexts are pruned to the still-pending permissions on each push and cleared on dispose, so permissions cleared on stop/interrupt can't leak.
- **Big files stay responsive.** Files over 1 MB skip the EDT-side hunk read/diff (full accept still works) so a large file can't freeze the UI when its permission card renders.
- **`sms:` links work again** — restored in the DOMPurify allowlist alongside `data:image/` inline images and the internal `jb:` scheme (`data:text/html` stays blocked).
- **Zero-deprecation build** — the rewind-fallback dialog moved off the deprecated `Messages.showYesNoDialog(…DoNotAskOption)` overload to `MessageDialogBuilder.yesNo`. Tests green and `verifyPlugin` Compatible across IC-252 → IU-262 EAP.

**Still deferred (small, low-value now):** selecting text from an *open diff* to attach, and an "expand/collapse all" button.

---

## v3.3.0 — 2026-06-04

**The whole binary→host protocol surface, mapped into the UI — plus native MCP elicitation.**

This release closes the loop on the native protocol: **every event the `claude` binary sends the host is now parsed *and* used** — answered when it's a request, surfaced in the chat when it carries information. The two control requests that used to fail with an error are handled correctly, and a batch of events that were parsed-but-invisible are now on screen. Under the hood it's all delegated to small single-responsibility collaborators (the chat session stays a thin orchestrator), and a new `./gradlew checkDrift` keeps the native models in step as the binary and SDK keep moving.

- **MCP elicitation, natively.** When an MCP server needs your input, you now get an **inline card** (never a blocking dialog): a URL flow shows an **Open link** + Accept/Cancel; a form renders a labeled field per schema property and sends back what you type on Accept. URL links are restricted to `http`/`https` — an untrusted server can't get a `file:`/`javascript:` link opened.
- **Predicted next prompt.** A `💡` chip above tdhe composer offers the binary's predicted follow-up; click to drop it into the input (you still review and send it yourself).
- **See the model think.** The status line shows a live reasoning-token estimate mid-turn.
- **Hooks, visible.** Each hook the binary runs appears as a single transcript row that updates from "running…" to ✓/✗ — no more silent hooks.
- **Memory recall, surfaced.** A collapsible "Recalled N memories" row shows exactly what context influenced a turn.
- **Smaller touches.** Tool-use summaries render as quiet notes, and file uploads now confirm success, not just failures. `request_user_dialog` requests are answered correctly instead of erroring.
- **Drift detection.** A new `./gradlew checkDrift` keeps the native protocol models in sync as the `claude` binary and its SDK evolve.

---

## v3.2.1 — 2026-06-04

**Pick your model provider — Anthropic or DeepSeek — without ever leaking your Anthropic credentials.**

- **Provider selector.** A new `Provider:` option (in Settings and as a composer chip, with each provider's brand logo) lets you point the `claude` binary at **Anthropic** (your normal subscription/login) or **DeepSeek** (its Anthropic-compatible endpoint). Switching restarts the session.
- **DeepSeek needs its own key — and that's enforced.** A non-Anthropic provider requires its **own issued key**, kept **isolated per provider in the IDE password safe** (never in a project file). Pick DeepSeek with no key and the plugin asks you to configure it first instead of switching.
- **Your Anthropic credentials are never used for another provider.** The endpoint and key are set together as a pair, the binary won't even load your Anthropic OAuth when a provider key is present, and the form refuses an Anthropic key (`sk-ant-…`) in the DeepSeek slot. `/login` only applies to Anthropic.

This release also fixes a pesky reasoning-regression: new "Thought process" blocks now respect the Ctrl+O toggle instead of always showing expanded — toggle reasoning off once and it stays off for every subsequent turn.

It's also a hands-on way to see what these "Anthropic-compatible" Chinese endpoints actually do with Claude Code's tool calls.

---

## v3.2.0 — 2026-06-04

**Composer polish, smarter links, real rollback — and a Vibe Coder Mode nobody asked for.**

- **A two-row options bar.** Model · mode · effort · thinking pills on top (centred, each with its own icon and a hover glow), the toggles + attach + a thin neon Play/Stop on the bottom. The prompt lights a coral focus ring while you type and uses your editor font.
- **Follow toggle.** A button keeps the streaming answer pinned to the bottom even if you scroll up; turn it off to read history mid-stream (it still follows while you're at the bottom). Ctrl+O collapsing the reasoning no longer jumps the scroll.
- **Attachments that actually work.** A file you attach is sent to the binary as a `@cwd-relative` mention it expands, and shows in your message as a clickable link. More file references in answers become clickable too — even without a line number (inside backticks), while staying conservative in prose.
- **Rollback, finished.** Reverting a Write that *created* a file now deletes it (not a 0-byte husk); reverting an edit restores the prior contents; every revert shows a confirmation. The in-card Diff/Revert buttons are now easy to hit.
- **Settings that fit your screen.** The settings page no longer stretches edge-to-edge on a wide monitor.
- **Sign in without leaving the chat.** `/login` no longer opens a terminal tab (which stopped working once JetBrains made the reworked terminal the default). It now opens your browser to approve access and asks you to paste the code right in the IDE — then signs you in and reconnects automatically.
- **🌈 Vibe Coder Mode.** An opt-in toggle that turns the whole chat neon-rainbow — send glyph, pills, boxes, icons, the focus ring — and swaps the Claude avatar for a Nyan Cat. Purely for the lulz; off by default.

Also in this release (the composer overhaul):

- **Paste images (Ctrl/Cmd+V).** Paste a screenshot straight into the composer — fixed on Linux (Wayland/X11) where the clipboard hands over a raw `image/…` stream; wired through the IDE action system so it follows your keymap on every OS.
- **A proper attachment menu.** Add the current file / selection / clipboard image, files and a directory from a native picker, or pick from your **open** and **recently-opened** files; chips show the real file-type icon and open the file when clicked.
- **Its own visual identity.** Custom icons on every tool call (bash, read, edit, search, web, task…), the attach button and chips, with the Claude coral as the accent — everything else still follows your IDE theme.
- **Diff History tab.** Lists every Edit/Write Claude made this session with a `+a/-b` summary, **View diff** and **Revert**, plus **Roll back all changes**.

`verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.1 — 2026-06-03

**Performance pass — the plugin was already faster than the CLI; now it flies.**

A focused optimization patch. Same features and behaviour as 3.0.0, materially lower latency and CPU, especially during streaming and on large sessions.

- **Streaming feels instant** — assistant/thinking deltas are coalesced and applied to the UI in a single hop per batch instead of one per token, cutting EDT churn dramatically.
- **Transcript renders only what changed** — a growing reply re-lays-out its own row, not the whole conversation; markdown is memoised and syntax highlighters are cached/reused.
- **Big sessions load fast** — restore reconstructs the recent tail (the binary keeps full context via `--resume`), and tool outputs anchor in O(1) instead of scanning the transcript.
- **Lighter under load** — one shared animation timer for all tool boxes (not one each), one quota poll per session (not per tab), and an O(n) stdout line splitter.

Includes a post-review hardening pass: a cap on the stdout buffer (no unbounded growth on a malformed stream), more robust streaming auto-scroll, orphan tool-result rows dropped on restore, the usage meter no longer blanks between turns, a synchronous delta drain on teardown, and markdown that re-renders on a theme switch. Plus two field-reported fixes: toggling thinking/model no longer closes the session (a restart race), and the edit diff now shows in **every** permission mode (acceptEdits / bypass / auto / dont-ask), not only when a permission card appears.

**Log in from the IDE.** `/login` used to dead-end with *"not available on this environment"* because the chat session has no interactive terminal. Now the plugin opens a real IDE terminal and runs `claude auth login` for you (launched with the binary's full path, so it works even when the IDE didn't inherit your shell `$PATH`). A detected auth failure also offers a one-click **"Log in in terminal"** prompt.

494 tests (0 failures) plus the gated UI suite; `verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.0 — 2026-06-03

**The whole Anthropic Agent SDK, nativized in JetBrains.**

3.0.0 turns the plugin from "a great chat" into a full native surface for the Claude Agent SDK protocol — every `system/*` and stream event parsed, every host→binary control request wired to a GUI control, and a redesigned, IDE-themed composer. Still no Node or TS SDK at runtime: the plugin speaks the binary's `stream-json`/control protocol directly.

**What's new**
- **Live session consumption** — a native panel (IDE progress bars + labels) above the composer shows the context window, the **authoritative cumulative token breakdown** (input / cache write / cache read / output, from the binary's `get_session_cost`) **inline**, and a quota bar with the reset countdown **and** absolute reset hour. The quota % shows when the binary reports it (no misleading 0%).
- **Tool-call state on the box** — each tool card is **sky-blue while in flight (pulsing sky↔amber)** and **green when finished**, with elapsed time while running.
- **Rich IDE attachments** — the 📎 button opens a selector menu (current file / selection / clipboard image), files/selections pin as chips, and you can **drag & drop or paste images** straight into the composer (native image content blocks). Large files are size-guarded and read off the UI thread.
- **Native "thinking" indicator** — the IDE's own animated spinner while a turn runs.
- **Subagent (Task) live strip** — one card per in-flight subagent with its running tokens / tool-uses / elapsed time and a Stop button; paused/failed states surface inline.
- **Advanced launch options** in Settings — max turns, max budget (USD), fallback model, extra `--add-dir` roots, beta flags, strict MCP config.
- **Plan mode & richer permissions** — ExitPlanMode plan cards, decision reasons, blocked-path context.
- **Session management** — rename, fork, and delete past sessions (the binary's session files stay the source of truth).
- **Native hooks** — `hook_callback` answered host-side via a pure decision engine (the real tool gate is still `can_use_tool`).
- **Diagnostics & account** — Account, Binary Version, Effective Settings, and an interactive MCP-runtime dialog (reconnect/toggle per server) in the gear menu.
- **A Diff button on every Edit/Write/MultiEdit row** plus syntax-highlighted code.

**Under the hood**
- The two former god-objects (`ClaudeSession`, `ChatPanel`) were decomposed into focused, individually-tested collaborators (token accounting, task tracking, transcript reconciliation, diff lifecycle, control client, permission cards, hooks, launcher; UI sub-panels for permissions/queue/subagents/attachments/usage). The orchestrators are now thin.
- A final hardening pass across security, architecture, concurrency, UX and protocol-fidelity: image read/encode moved off the EDT, the absolute reset-hour and a non-colour warning marker restored, subagent status/error updates wired, shared token formatting, and more.
- A native-UI + correctness pass from hands-on testing: the consumption readout rebuilt from native components, usage sourced from the binary's authoritative `apiUsage`, human-readable permission-mode labels, the model default shown as "Default · Opus 4.8", a responsive (scrollable) Settings page, and a fix so approving a plan no longer leaves the Mode chip stuck on "plan".
- **474 automated tests** (0 failures) across unit / headless / integration, plus the gated RemoteRobot UI suite (locators validated against a live IDE). Verified Compatible against IU-261 and IU-262 (RC), with zero deprecated/internal APIs.

---

## v2.2.2 — 2026-06-03

**Full automated test pyramid + release automation — same runtime as 2.2.0**

Completes the testing and maintenance foundation started in 2.2.1. End-user behaviour is unchanged; under the hood every layer of the plugin is now covered by automated tests and releases are automated end-to-end.

**Test pyramid (now complete)**
- **Headless component tests** run the IntelliJ Platform in-process to cover services and Swing wiring that pure unit tests can't reach (diff registry, session manager, settings UI, real token accounting).
- **Integration tests** drive a real `ClaudeSession` against a deterministic `fake-claude` stand-in via JSONL fixtures — init, streaming, thinking, token fold, rate-limit, tool permission, resume, interrupt, and the auto-approve cascade regression.
- **End-to-end UI tests** (RemoteRobot) cover the click-paths a user actually takes; they run in a nightly workflow.
- **239 tests** in the default suite (0 failures), plus the gated UI suite.

**Release automation**
- Tag a `vX.Y.Z` and `release.yml` runs the full test + verifier gate, then signs and publishes to the Marketplace and cuts a GitHub Release. A nightly `ui-tests.yml` runs the RemoteRobot suite under Xvfb. `docs/BRANCHING.md` captures the GitFlow + branch-protection conventions.

---

## v2.2.1 — 2026-06-03

**Test pyramid + maintenance workflow — same runtime as 2.2.0, professional foundation underneath**

This release is the **infrastructure update**: the plugin's behaviour is identical to 2.2.0 but it now ships with the CI, documentation, drift detection, and test scaffolding that a Marketplace-listed plugin with real users deserves.

**Test pyramid foundations**
- **202 tests** (+67 since 2.2.0): direct security-gate coverage for `DiffPresenter.isWithinRoot` (incl. symlink escape attempts), exhaustive `PermissionBroker` matrix, `ClaudeBinaryLocator` cross-platform, `McpConfigBuilder` full transport coverage, `parseAskQuestions`, and `MarkdownRenderer` edge combinations.
- New Gradle source sets `integrationTest` and `uiTest`. The `integrationTest` task drives the plugin against `bin/fake-claude`, a deterministic Python stand-in fed JSONL fixtures from `src/integrationTest/resources/fixtures/` — Layer C scenarios will land here in 2.2.x. `uiTest` is reserved for the RemoteRobot end-to-end suite.
- `kotlinx-kover` coverage report (`./gradlew koverHtmlReport`).

**CI + maintenance workflow**
- `.github/workflows/ci.yml`: runs tests, `verifyPlugin`, and `buildPlugin` on every push and PR, uploading the plugin zip as an artifact.
- `.github/workflows/sdk-drift.yml` (weekly), `binary-drift.yml` (daily), `binary-probe.yml` (weekly + manual): open issues automatically when a newer SDK or `claude` binary is released, or when the binary starts emitting an event type the plugin doesn't parse.
- `SECURITY.md` (responsible disclosure + SLA), `CONTRIBUTING.md`, `CODEOWNERS`, issue + PR templates, Dependabot for Gradle and the SDK reference.

**Documentation**
- `docs/RELEASE_PROCEDURE.md` + `docs/RELEASE_CHECKLIST.md` (end-to-end release flow), `docs/BINARY_COMPAT.md` (which `claude` binary each plugin version was tested against), `docs/FAQ.md`, `docs/TROUBLESHOOTING.md`, `docs/TELEMETRY.md` (plain answer: we collect nothing).

**Why 2.2.1 instead of folding into 2.2.0**: 2.2.0 is the Marketplace-publishable bug-fix release that unblocked publication (`findEnabledPlugin` migration). 2.2.1 cleanly separates the runtime fixes from the maintenance/test infrastructure so the changelog tells the truth.

---

## v2.2.0 — 2026-05-28

**Marketplace-publishable, live model picker, links inside backticks, aligned with `claude` 2.1.161**

Compatibility + UX iteration on top of 2.1.0. 134 tests.

**Marketplace fix**
- Migrated the bundled MCP plugin lookup from an `@ApiStatus.Internal` API to the public static `PluginManager.getPlugin(PluginId)`. This was the lone internal-API hit blocking the upload re-check; the plugin is now Marketplace-acceptable.

**Model picker**
- The Settings model combo shows the binary's actual options (`Default (recommended)` = Opus 4.8 with 1M context, `Sonnet` = Sonnet 4.6, `Haiku` = Haiku 4.5) by their human label, and **refreshes live** when the `initialize` handshake lands — so opening Settings before the session warms up no longer leaves only the historical Opus tags.
- New installs default to `default` (the binary picks the recommended tier) instead of the hard-coded `claude-opus-4-7`. Existing settings keep what you had.

**Linkify in backticks**
- `` `src/Foo.kt:42` `` references (the natural way the model writes paths in code answers) now render as clickable `jb://open` links wrapped in `<code>`, instead of inert monospaced text. Still confined to the project root.

**Protocol**
- Bumped the SDK reference to **0.3.161**, aligned with `claude` **2.1.161**. New `ModelInfo` fields (`supportsEffort`, `supportedEffortLevels`, `supportsAdaptiveThinking`, `supportsFastMode`, `supportsAutoMode`) and `AccountInfo` fields (`apiProvider`, `apiKeySource`) are now decoded; new `system/*` events the binary now emits (`task_progress`, `task_notification`, `background_task_*`, `auth_status`, `session_state_changed`) are tolerated — UI integration in a follow-up.

---

## v2.1.0 — 2026-05-27

**Review edits anytime, readable questions, partial acceptance, sessions from the binary's own files, and more**

A round of native-only UX features, backed by new tests (132 total).

**Edit review**
- **Persistent diff from the transcript** — every Edit/Write/MultiEdit keeps a "View diff" button on its tool card, so you can re-open the old↔new diff at any time, in any permission mode (even after an auto-approved edit). The pre-write file contents are snapshotted at approval time and keyed to the tool call.
- **Hunk-by-hunk acceptance** — the permission card now lists the change's hunks with checkboxes; accept only the ones you want and the binary writes exactly that subset (the file path is never altered).

**Readability**
- **AskUserQuestion options** now wrap — long labels, descriptions and the (previously unused) per-option preview are shown in full instead of being clipped to one line.
- **Better Markdown** — strikethrough, GFM task-list checkboxes, nested lists, and a fix for double-linkified URLs.

**Editor integration**
- **"Explain with Claude"** in the editor right-click menu sends the current selection (with its file path) to the active chat.
- **Jump-to-code** — `path:line` references in Claude's replies become clickable links that open the file at that line (confined to the project tree).

**Permissions**
- **"Always allow" per tool** — approve a tool once and skip its prompt for the rest of the project; reviewable writes still stay confined to the project root. Settings lists the remembered tools with a Remove button, so you can revoke any rule.

**Sessions**
- **Reads the binary's own session files** — past conversations come straight from Claude Code's transcripts (`~/.claude/projects`), so the plugin never stores a copy of your chats. **Open Previous Session…** lists the project's sessions by their real title (the one `--resume` shows) and re-attaches with `--resume`.
- **Restore on startup** — the tabs you had open are reopened automatically; if none were recorded, your most recent session is restored. Turn it off in Settings ▸ Claude Code ▸ "Restore open chats on startup".
- A background session that needs attention (pending permission, finished turn, or error) raises a notification and a tab badge — suppressed only for the chat currently on screen; the notification's **Open** button dismisses it.

**Security**
- Jump-to-code links are confined to the project root (a crafted `path:line` can't open `~/.ssh`, `/etc`, or `..`-traversed files), and explicit Markdown links are restricted to an allow-list of schemes with their href escaped.
- The plugin writes **no conversation content** to project files — only which tabs were open (in `workspace.xml`). Session-file reads stay inside `~/.claude/projects` behind a UUID-shaped id check, so a crafted session id can't traverse out.

**Fixes**
- Reasoning shows again on current models: extended thinking now uses the launch flags `--thinking adaptive --thinking-display summarized` (the old `set_max_thinking_tokens` control stopped surfacing it). Thinking is on/off — adaptive, the model decides depth.
- No more "Write-unsafe context!" crash when refreshing edited files (the VFS refresh is now asynchronous).

---

## v2.0.1 — 2026-05-27

**Compatibility update**

- Extended the supported IDE range to the current EAP: `until-build` is now `262.*`, so the plugin installs and runs on the 2026.2 EAP builds (verified Compatible against IU-262 with `verifyPlugin`).
- Replaced the internal `PluginManagerCore` API used to locate the bundled MCP Server plugin with the public `PluginManager` lookup by plugin id (`com.intellij.mcpServer`), removing the only internal-API usage and dropping the fragile path-name heuristic.

---

## v2.0.0 — 2026-05-26

**Reliability & security hardening + first unit-test suite**

This release is a stability milestone: a multi-profile review (security, clean-code, SRE) drove a round of fixes across the process lifecycle, permission handling and the protocol layer, backed by the project's first automated tests.

**Reliability**
- Fixed an EDT freeze on session start: resolving the process environment (which sources a login shell, up to a multi-second timeout) and spawning the binary now run off the UI thread; the resolved environment is cached per session. The IDE no longer hangs when opening the first chat or sending the first prompt.
- In-flight control requests (`get_context_usage`, session cost, MCP status, the initialize handshake) are now resolved when the process stops or crashes, instead of leaving dialogs stuck on "Loading…".
- Control requests get a 30s watchdog, so a hung binary no longer leaves a callback pending forever.
- Process start failures are now surfaced (notification + log) instead of leaving a half-initialized "ready" session; writes to a dead stdin are logged instead of silently dropped.

**Security**
- Auto-approved file writes (in `acceptEdits` / `bypassPermissions`) are now confined to the project root: a write whose path resolves (symlinks included) outside the project falls back to a manual Accept/Reject card.
- Trust-on-open gate: if a project's `claude-code.xml` carries a source script or a custom stdio MCP server (both execute code at launch), the plugin asks for confirmation once before running them.
- The source-script argument is now passed without shell interpolation (no injection via a crafted path), and Settings now warns that environment variables are stored in plain text and that the source script is executed on start.

**Quality**
- First unit-test suite (80 tests): protocol parsing/building, diff reconstruction, transcript hierarchy, rate-limit math, environment parsing.
- Internal cleanups: MCP config building extracted to a testable unit, thread-safe tab counter, named constants, and quieter-failure logging.

---

## v1.3.5 — 2026-05-26

**IDE tools over MCP (opt-in)**
- New section in Settings ▸ Claude Code with two independent controls:
  - **Enable JetBrains MCP server** — let Claude query the IDE (diagnostics, open files, usages, …) through JetBrains' own MCP Server plugin. Choose the transport (`sse`, `streamable-http`, `stdio`) and port. For `sse`/`streamable-http` the localhost endpoint is filled in for you; **`stdio` is assembled automatically from the running IDE** (its JBR `java` and bundled `mcpserver` libs), so there's nothing to paste and it works on Windows unchanged.
  - **Custom MCP servers** — add as many of your own MCP servers as you like, as a JSON object (`name → server config`).
- Off by default. Every IDE tool call is still gated by the in-chat permission prompt; enable only on a machine you trust.

> Requires JetBrains' **MCP Server** plugin enabled (Settings ▸ Plugins) for the JetBrains option.

---

## v1.3.1 — 2026-05-26

**Fixes & defaults**
- Fixed the Settings model dropdown showing empty when opened before the initialize handshake — it now always lists available models plus known fallbacks.
- Removed the blank entry from the Effort dropdown in Settings.
- Default model is now **Opus 4.7**; default effort is now **medium**.

---

## v1.3.0 — 2026-05-26

**Windows support**
- The `claude` binary is now detected on Windows (`claude.exe` / `claude.cmd`) across npm, scoop, volta, chocolatey and `~\.local\bin`.
- npm `.cmd` shims are driven as `node cli.js` directly, bypassing cmd.exe — which corrupted the streaming stdio pipe (stdin EOF triggered "Terminate batch job (Y/N)?") and mangled argument quoting. This fixes both the "not a valid Win32 application" (error 193) failure and the "found but no response" hang.

**Configurable paths & environment**
- Explicit overrides for the `claude` and `node` executable paths in Settings — the catch-all for custom install dirs, version managers, or a GUI IDE that doesn't inherit the user's PATH.
- Configurable environment variables (`KEY=VALUE` per line) injected into the binary's process — useful on Windows for `PATH` additions.
- **Source script**: point to a `.sh` (sourced in the login shell on Linux/macOS) or a PowerShell profile/`.ps1` (dot-sourced on Windows); its resulting environment is captured and applied to the `claude` process, so the IDE inherits the same `PATH`/setup as the user's own shell.
- The "binary not found" notification now offers a **Configure paths…** action that jumps straight to the settings page.
- The auto-detected `claude` path is persisted on first launch (and refreshed when stale), so startup is stable and the path is visible/editable.

---

## v1.2.0 — 2026-05-26

**Hierarchical, collapsible transcript**
- Every tool call is now a collapsible group: a disclosure triangle on the tool card shows or hides its output.
- Subagent (`Task`/Agent) activity nests under its Agent — the subagent's own tool calls, outputs and text are anchored and indented beneath the Agent card. Collapsing is hierarchical: collapse the Agent to fold its whole subtree, or collapse a single sub-tool to fold just its output.
- Tool outputs now anchor directly under their tool call instead of drifting to the end of the transcript. This fixes outputs of tools that require human interaction (permission cards, `AskUserQuestion`) and long-running/parallel calls, where the result previously landed at the tail.

**Build**
- `JBUI.scale` → `JBUIScale.scale` (correct API for stroke scaling in IntelliJ Platform 2025+).

**Info bar**
- Reordered: (1) Resets in countdown, (2) Reset Hour, (3) Session Usage %, (4) Brewing / live tokens / Esc to interrupt.

---

## v1.1.0 — 2026-05-26

**Quota & rate-limit fixes**
- Quota bar stays visible with reset countdown when utilization % is not reported (Max plans); % meter hides independently.
- `isWarning` / `isExhausted` no longer fire on `overageStatus = "rejected"` alone.
- Token counter now accumulates correctly across multi-message turns (tool calls, chained assistant messages).

**Session reliability**
- Failed turns with no `result` text (`error_*` subtypes) surface the `errors` list or subtype name — no more silent failures.
- `dispose()` sends EOF before killing the process (clean exit, same order as `stop()`).
- `LiveUsage` updates moved to EDT to eliminate read-modify-write race on token counters.
- `ready` and `process` marked `@Volatile` — visibility gap on session start/stop across threads fixed.
- Startup queue flushed after `system/init` — messages sent before the handshake are no longer dropped.

**Protocol**
- `errors: List<String>` field added to `ResultMessage` to capture SDK `SDKResultError.errors` payloads.

---

## v1.0.0 — 2026-05-26

First stable release.

### Features

**Streaming chat**
- Real-time token streaming with animated thinking indicator and live token counter
- Multi-tab support — open independent sessions per project
- Multi-prompt queue: type while the agent is working, messages are sent in order when the turn ends
- `/btw` side-questions sent mid-turn without interrupting the active turn
- Interrupt (Esc) to stop the current turn at any time

**Full slash-command palette**
- All commands from the `claude` binary are surfaced natively (Ctrl+K / Cmd+K)

**Model & runtime controls**
- Model selector chip (all models available in your account)
- Permission mode chip (default / acceptEdits / bypassPermissions)
- Effort chip (low / medium / high) for extended thinking
- Thinking token budget (Ctrl+O to toggle)

**Native diff review for file edits**
- When the agent requests to write or edit a file, a diff opens inline in the editor area
- Non-modal Accept / Reject card in the chat — no popups or modal dialogs
- On acceptance the binary writes the file; VFS is refreshed automatically

**Permission & question handling**
- `can_use_tool` requests surface as native inline cards (Accept / Reject)
- `AskUserQuestion` rendered as a structured question card with option buttons
- Auto-approve in `acceptEdits` / `bypassPermissions` modes

**Quota bar**
- Subscription usage % shown when the binary reports it (near the usage limit)
- Displays reset window countdown and overage status

**Settings**
- Configurable default model, permission mode, effort, thinking tokens
- Allowed / disallowed tools, setting sources, output style
- All settings accessible via Settings → Tools → Claude Code

**IDE integration**
- Tool window anchored to the right panel (same area as AI Assistant)
- Light and dark theme support with custom icons
- Works in any IntelliJ Platform IDE (IDEA, PyCharm, WebStorm, GoLand, etc.)

### Requirements

- JetBrains IDE 2024.3 – 2025.1.x (build 243–261)
- [`claude` binary](https://claude.ai/code) installed and on `PATH` or `~/.local/bin/`
- Claude subscription (claude.ai) or `ANTHROPIC_API_KEY` environment variable

