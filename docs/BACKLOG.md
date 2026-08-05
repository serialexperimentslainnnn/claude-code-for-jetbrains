# Backlog

Things worth doing, with enough evidence attached that the next person does not have to re-derive whether they
are possible. An entry here has been **probed against the real binary**, not assumed from the SDK types.

Ordered by value, not by effort.

---

## 1. Surface the plan's usage limits in the session dashboard

**Status: NOT backlog — scheduled, and being built now.** Kept here because the evidence below is the useful
part and belongs next to the other protocol findings.

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

## 2. Use `get_workspace_diff` for a session-wide review

**Status:** probed, returns `{"diff": null}` on a clean tree — the request works, we have simply never sent it.

The plugin reviews changes **per tool call**: a diff tab per Edit, and the transcript's inline diff. What it
cannot answer is "show me everything this session changed", which is exactly the question you ask before
accepting a long autonomous run. `get_workspace_diff` returns that in one call.

Natural home: a button in the session dashboard, next to Diff History (which is per-edit and IDE-side).

---

## 3. Surface the active plan with `get_plan`

**Status:** probed, returns `{"exists": false}` when there is none.

In plan mode the plan is visible only as the transcript card that proposed it; scroll past and it is gone.
`get_plan` fetches the current one on demand, so the dashboard could always show what the agent is working to.

---

## 4. Deliberately NOT worth doing

Recorded so nobody re-investigates them.

- **`file_suggestions`** — probed, works, returns `{"suggestions": [...]}` for a query. But the IDE's own file
  index already backs the @-mention picker and is strictly better: it knows about excluded folders, scopes and
  recency, and it answers without a round-trip through the binary.
- **`list_models`** — probed, returns the full catalogue. Redundant: the model list already arrives in the
  `initialize` reply and is cached, so sending this would be a second source of truth for the same data. The
  existing decision was right; this entry exists so it is not revisited a third time.

---

## 5. Split `ClaudeSession` (carried over from the 5.0.0 static-analysis pass)

**Status:** the two remaining `config/detekt/baseline.xml` entries.

`ClaudeSession` is ~1900 lines with 46 public functions. Ten collaborators have already been extracted from it;
what remains is genuine orchestration plus the verb list the UI calls. The real fix is a split into
session-lifecycle versus UI-facing-commands — a large, behaviour-preserving refactor of the hottest file in the
repository, which belongs in its own reviewed change. The baseline file carries the full reasoning, including
why raising the thresholds instead would be worse.

---

## 6. Tighten the coverage gates when Kover allows it

`KoverVerifyRule` in Kover 0.9.2 has no per-rule filter (verified against the plugin jar), so the per-package
thresholds in `docs/RELEASE_CHECKLIST.md` §Coverage policy are enforced today as a floor plus an aggregate
rather than package by package. If a later Kover adds per-rule filters, tighten `build.gradle.kts` to match the
table that is already written there.
