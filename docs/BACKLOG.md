# Backlog

Things worth doing, with enough evidence attached that the next person does not have to re-derive whether they
are possible. An entry here has been **probed against the real binary**, not assumed from the SDK types.

Ordered by value, not by effort.

---

## 1. Surface the plan's usage limits in the session dashboard

**Status: DONE — the panel shipped in 5.0.0, and 5.1.0/5.1.1 finished it.** Kept here because the evidence
below is the useful part and belongs next to the other protocol findings. Two things the entry did not
anticipate and the build found:
per-model windows arrive in `rate_limits.model_scoped`, which the binary *synthesises* behind a remote config
and therefore often omits (so the raw `rate_limits.limits[]` array is read too), and a failed usage fetch
degrades to a header-seeded reply carrying only `five_hour`/`seven_day`, which is why a refresh is merged into
the previous one rather than replacing it.

The web and desktop Claude apps show, at a glance: current session usage with a reset countdown, weekly usage
across all models, weekly usage per model, and the extra-credit balance. The plugin shows a single quota bar
driven by whichever `rate_limit_event` arrived last. For someone on a Max plan doing long sessions, "how much
of my week have I burned" is the single most consulted number, and today they have to leave the IDE for it.

### The data is already there — we simply never ask

`get_usage` is a host→binary control request the plugin has known about since 4.0.1 and **has never sent**. It
was triaged into `ProtocolSurface.KNOWN_SUBTYPES` as out of scope; that call has aged badly. Probed live
against `claude` 2.1.222:

```jsonc
{
  "subscription_type": "max",
  "rate_limits_available": true,
  "rate_limits": {
    "five_hour":  { "utilization": 8,  "resets_at": "2026-08-06T00:10:00Z", "limit_dollars": null, … },
    "seven_day":  { "utilization": 67, "resets_at": "2026-08-06T17:00:00Z", … },
    "seven_day_opus": null, "seven_day_sonnet": null, "seven_day_cowork": null, …,
    "extra_usage": { "is_enabled": true, "used_credits": 14612, "currency": "EUR", "decimal_places": 2, … }
  },
  "session": { "total_cost_usd": …, "total_duration_ms": …, "model_usage": { … } }
}
```

That is a one-for-one match with what the apps display, including the per-model weekly buckets (null only
because those windows were untouched at probe time) and the credit balance.

### Two things to fix on the way

- **`ClaudeSession.rateLimit` is a single field.** `rate_limit_event` carries a `rateLimitType`
  (`five_hour` | `seven_day` | `seven_day_opus` | …), so consecutive events for different windows **overwrite
  each other**. By construction the plugin can only ever display one window. Showing several needs a
  `Map<rateLimitType, RateLimitInfo>` — a small change, but it is the actual blocker, not the UI.
- **`RateLimitInfo` does not model everything the wire sends.** A captured event carried `overageResetsAt` and
  `overageInUse`; neither is in the data class. Small, real protocol drift — and the kind `checkDrift` is
  supposed to catch, so it is worth understanding why it did not.

### Design note

Prefer `get_usage` as the source of truth (it returns every window at once, on demand) and keep
`rate_limit_event` as the live nudge that something changed and it is worth re-asking. Poll sparingly: this is
a network round-trip through the binary, and a dashboard that refreshes on a timer for a number that moves
every few minutes is a cost with no user visible in it.

---

## 2. Give every running agent its own tab and its own transcript

**Status: DONE — shipped in 5.5.0.** Kept for the one finding that inverted the plan, because anyone
extending this will otherwise reach for the same wrong primitive.

The plan above assumed the tree had to be **reconstructed** from the event stream, by joining `tool_use_id`
to the messages carrying that `parent_tool_use_id` to the `task_started` events born inside them. It does
not: **a nested agent's `task_started` never reaches the main stream at all**, so no amount of joining
recovers anything below the first level. The binary already writes the whole tree to disk —
`<sessionId>/subagents/agent-<id>.meta.json` carries `{agentType, description, toolUseId, parentAgentId,
spawnDepth}` next to `agent-<id>.jsonl`, the transcript in the ordinary session format — so `AgentRegistry`
reads the sidecar for parentage and `SessionTranscriptReader.parseEntries` (the parser session restore
already uses) for the content.

What that bought, and what it cost:

- **Admission, not enumeration.** That directory also holds agents a terminal `--resume` left behind — 84 of
  them in the session this came from. An agent is shown only if the plugin saw the `Task` call, or
  `PluginAgentIndex` (`~/.claude/ide/claude-code-native/agent-index.json`) recorded it in a previous run, or
  its parent is already admitted. The last rule is load-bearing, for the same reason as above.
- **The bare id is the identity.** The file is `agent-<id>.jsonl` but the sidecar says `parentAgentId:
  "<id>"`, unprefixed. Taking the filename as the id collapses the tree into one level.
- **A nested subagent has no `toolUseId`**, so nothing can settle it from the event stream; it inherits its
  parent's ending, since it cannot outlive the turn that spawned it. For agents restored from a previous run
  there is no live status at all, so `AgentEnding` reads the last record of the agent's own transcript.
- **Background-task ownership** landed as this entry predicted it would have to: the level signal
  (`background_tasks_changed`) carries no parent, so the link comes from the structured tool output's
  `backgroundTaskId`, and a task seen without one is shown without an owner rather than given an invented
  chain.

---

## 3. Use `get_workspace_diff` for a session-wide review

**Status:** probed, returns `{"diff": null}` on a clean tree — the request works, we have simply never sent
it. Still true at 5.5.0: the subtype is triaged in `ProtocolSurface.KNOWN_SUBTYPES` and there is no builder
for it in `ControlProtocol`.

The plugin reviews changes **per tool call**: a diff tab per Edit, and the transcript's inline diff. What it
cannot answer is "show me everything this session changed", which is exactly the question you ask before
accepting a long autonomous run. `get_workspace_diff` returns that in one call.

Natural home: a button in the session dashboard, next to Diff History (which is per-edit and IDE-side).

---

## 4. Surface the active plan with `get_plan`

**Status:** probed, returns `{"exists": false}` when there is none. Also still un-sent at 5.5.0, and for the
same reason — triaged as known, never built.

In plan mode the plan is visible only as the transcript card that proposed it; scroll past and it is gone.
`get_plan` fetches the current one on demand, so the dashboard could always show what the agent is working to.

---

## 5. Deliberately NOT worth doing

Recorded so nobody re-investigates them.

- **`file_suggestions`** — probed, works, returns `{"suggestions": [...]}` for a query. But the IDE's own file
  index already backs the @-mention picker and is strictly better: it knows about excluded folders, scopes and
  recency, and it answers without a round-trip through the binary. (`ControlProtocol.fileSuggestionsRequest`
  exists as a builder and has **no caller** — the request is modelled, not adopted.)
- **`list_models`** — probed, returns the full catalogue. Redundant: the model list already arrives in the
  `initialize` reply and is cached, so sending this would be a second source of truth for the same data. The
  existing decision was right; this entry exists so it is not revisited a third time.

---

## 6. Split `ClaudeSession` (carried over from the 5.0.0 static-analysis pass)

**Status:** still the two — and only two — entries in `config/detekt/baseline.xml` (`LargeClass` and
`TooManyFunctions`, both on `ClaudeSession`).

The file has **grown** since this entry was written, not shrunk: just over 2 500 lines at 5.5.0, against the
~1 900 it recorded. Collaborators keep being extracted from it (five more in 5.5.0 alone, counting the ones this release
added) and it keeps re-filling, which is the argument for the split rather than against it: what remains is
genuine orchestration plus the verb list the UI calls, and those are two responsibilities sharing one file.
The real fix is a split into session-lifecycle versus UI-facing-commands — a large, behaviour-preserving
refactor of the hottest file in the repository, which belongs in its own reviewed change. The baseline file
carries the full reasoning, including why raising the thresholds instead would be worse.

---

## 7. Tighten the coverage gates when Kover allows it

`KoverVerifyRule` has no per-rule filter — re-checked at **0.9.9**, the version the build uses, against the
DSL reference and the release notes rather than only the jar. So the per-package thresholds in
`docs/RELEASE_CHECKLIST.md` §Coverage policy are enforced today as a floor applied to every package plus an
aggregate, rather than package by package. If a later Kover adds per-rule filters, tighten `build.gradle.kts`
to match the table that is already written there.
