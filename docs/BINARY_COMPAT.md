# Binary compatibility

This document tracks which `claude` binary versions each plugin release
has been tested against, and which stream-json / control events the
protocol layer currently understands.

The plugin uses **lenient JSON decoding** (`ignoreUnknownKeys = true`) in
`ProtocolParser`, so a newer binary that adds fields to existing events
will not break older plugin versions — they just won't render the new
data. **Entirely new event types** require code changes in
`protocol/ClaudeEvent.kt` (and downstream in `ClaudeSession` /
`TranscriptView`) before the plugin can react to them.

## Version matrix

| Plugin version | claude binary min | claude binary tested | SDK ref         | Notes                                                                                  |
|----------------|-------------------|----------------------|-----------------|----------------------------------------------------------------------------------------|
| 2.0.1          | 2.1.150           | 2.1.150              | 0.3.150         | First Marketplace release.                                                              |
| 2.1.0          | 2.1.150           | 2.1.150              | 0.3.150         | **Not published** — blocked by internal-API usage discovered during `verifyPlugin`.    |
| 2.2.0          | 2.1.150           | 2.1.161              | 0.3.161         | Current release. Lenient codec absorbs new fields; new event types listed below.        |

Bump the **min** column only when the plugin starts depending on a feature
older binaries do not expose; otherwise leave it conservative.

## Events currently parsed

`protocol/ClaudeEvent.kt` and `ProtocolParser` understand at least:

- `system/init` (carries `session_id`, `slash_commands`, launch metadata).
- `assistant` (content blocks: text, thinking, tool_use).
- `user` (echoed user turns, tool_result).
- `stream_event` (assistant deltas while `--include-partial-messages`).
- `result` (end of turn, usage, stop reason).
- `keep_alive` (ignored).
- `control_request` with `subtype = can_use_tool` (permissions and
  `AskUserQuestion` piggy-backed on it).
- `control_response` correlated by `request_id`.
- `status` / `compact_metadata` (compaction reconstruction).

## Events from binary 2.1.161 — TO IMPLEMENT

The newer binary emits additional events that the plugin currently
ignores via lenient decoding. They should be added incrementally as
sealed-subclass cases in `ClaudeEvent.kt`, with rendering in the
transcript and, where relevant, hooks in `ClaudeSession`:

- `task_progress` — long-running task progress updates (percentage,
  step description). UI: progress strip on the corresponding ToolRow.
- `task_notification` — completion / failure notifications for
  background work. UI: attention badge + optional toast via the existing
  `SessionListener.onAttention` plumbing.
- `background_task_started` / `background_task_finished` — lifecycle of
  detached agent tasks. UI: collapsible section in the transcript;
  cancel via existing `interrupt` control.
- `auth_status` — re-auth required, quota exhausted, plan downgrade.
  UI: actionable notification with a "Re-authenticate" action that runs
  `claude login` via the binary.
- `session_state_changed` — server-side state transitions (e.g.
  paused / resumed, mode coerced). UI: reflect in the mode chip without
  letting the binary override the plugin's source-of-truth mode.

Each new event should land with:

1. A sealed-subclass entry in `ClaudeEvent.kt` annotated with
   `@SerialName(...)`.
2. A parser test in `src/test/kotlin/.../protocol/` with a captured
   JSONL fixture.
3. Optional rendering in `TranscriptView` and listener calls in
   `ClaudeSession`.
4. An entry in `CHANGELOG.md` under `Added` and a row update here.

## When the binary changes

When a Dependabot PR bumps
`node_modules/@anthropic-ai/claude-agent-sdk/` (label `sdk-drift`),
the reviewer should:

1. Diff `sdk.d.ts`, `sdk-tools.d.ts`, and `sdk.mjs` against the previous
   version.
2. List any new event subtype, new control subtype, or changed field
   semantics in the PR description.
3. Decide per item: ignore (lenient codec covers it), add a parser case,
   or surface in the UI.
4. Update the "TO IMPLEMENT" list above and the version matrix once a
   release ships against the new binary.
