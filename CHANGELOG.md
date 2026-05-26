# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.1.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lain/claude-code-for-jetbrains/releases/tag/v1.0.0
