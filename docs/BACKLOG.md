# Backlog

Things worth doing, with enough evidence attached that the next person does not have to re-derive whether they
are possible. An entry here has been **probed against the real binary**, not assumed from the SDK types.

Ordered by value, not by effort.

---

## 1. Surface the plan's usage limits in the session dashboard

**Status: DONE — shipped in 5.1.0 and 5.1.1.** Kept here because the evidence below is the useful part and
belongs next to the other protocol findings. Two things the entry did not anticipate and the build found:
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

**Status: requested, evidence gathered, not started.** The largest UI change since 4.0.0.

Today every subagent's output lands in the one transcript of the chat that spawned it. On an ordinary session
that is fine. On a session running agents under agents plus a pile of background tasks — the case this came
from — it stops being usable: the main transcript fills with consecutive "Thought process" rows belonging to
different agents, interleaved, with no way to follow any single one of them, and the panel visibly strains.

### What it should become

- **A row of agent tabs under the chat tabs**, one per running agent of the **selected chat**, each with its
  own transcript: its thinking, its tool calls, its output. Switching chats swaps the row for that chat's
  agents — an agent belongs to its chat, and nothing from another chat is ever shown.
- **Nesting**: an agent that spawns subagents gets a further row listing them, same treatment, recursively.
- **The main transcript stops mixing.** A Task/Agent call renders as a **link to that agent's tab** and its
  output no longer interleaves. This is the trade that pays for the feature: the main transcript becomes
  readable again precisely because the detail moved somewhere it can be read.
- **The session dashboard states ownership**: for each agent, which chat and which agent chain it runs under,
  with a link to it; same for each **background task** — which chat, under which agent(s).

### What the protocol gives (verified against `sdk.d.ts` @ 0.3.226 and the plugin's models)

- `system/task_started` carries `task_id`, **`tool_use_id`**, `description`, `subagent_type`, `task_type`,
  `workflow_name`, `prompt` and `skip_transcript`. That last field is the SDK explicitly anticipating this
  feature: *"Ambient/housekeeping task. Consumers should hide this from the inline transcript; it may still
  appear in a tasks panel."*
- Every `assistant`/`user` message carries **`parent_tool_use_id`**, and the plugin already models the
  relation: `TranscriptModel` keeps `parentOf`, plus `isDescendantOf` and `insertionIndexFor`, and
  `TranscriptReconciler.addSubagentText` already routes subagent text by that id. The routing primitive
  exists; what is missing is a place to route it *to*.

### What the protocol does NOT give — and what that costs

- **`task_started` has no parent field.** The chain is not given: it has to be reconstructed by joining
  `tool_use_id` → the messages carrying that `parent_tool_use_id` → the `task_started` events born inside
  them. Derivable to N levels, but it is *our* reconstruction, so it must be built to degrade into a flat
  list rather than into a wrong tree.
- **`system/background_tasks_changed` carries only `task_id`, `task_type`, `description`** — no parent, no
  `tool_use_id`, no agent — and has REPLACE semantics. The chat is known (it is the session that received the
  event); **the owning agent is not**. Where a `task_id` was seen earlier in a `task_started` the chain can be
  recovered; where it was not, the dashboard must say *"no known agent"* rather than invent a chain. Stating
  this up front so nobody promises the full ownership line for every background task.

### Probe before building (needs a live heavy session)

1. Does a second-level `task_started` arrive with the **subagent's** `tool_use_id` (chains) or with the main
   turn's (everything flat)? This decides whether nesting is real or cosmetic.
2. Do each subagent's text and tool calls carry their **own** `parent_tool_use_id`, deep enough to route every
   block to the right tab?

Instrument it the way the `get_usage` reply was instrumented — one temporary INFO line printing the wire —
and read it against a session that is actually running agents under agents.

### Suggested order

The part that hurts today does not depend on the answers: **stop interleaving subagent output in the main
transcript and leave a link in its place**. That is shippable on its own and immediately makes the heavy
session readable. Deep nesting, dashboard ownership lines and background-task attribution follow, with data.

---

## 3. Use `get_workspace_diff` for a session-wide review

**Status:** probed, returns `{"diff": null}` on a clean tree — the request works, we have simply never sent it.

The plugin reviews changes **per tool call**: a diff tab per Edit, and the transcript's inline diff. What it
cannot answer is "show me everything this session changed", which is exactly the question you ask before
accepting a long autonomous run. `get_workspace_diff` returns that in one call.

Natural home: a button in the session dashboard, next to Diff History (which is per-edit and IDE-side).

---

## 4. Surface the active plan with `get_plan`

**Status:** probed, returns `{"exists": false}` when there is none.

In plan mode the plan is visible only as the transcript card that proposed it; scroll past and it is gone.
`get_plan` fetches the current one on demand, so the dashboard could always show what the agent is working to.

---

## 5. Deliberately NOT worth doing

Recorded so nobody re-investigates them.

- **`file_suggestions`** — probed, works, returns `{"suggestions": [...]}` for a query. But the IDE's own file
  index already backs the @-mention picker and is strictly better: it knows about excluded folders, scopes and
  recency, and it answers without a round-trip through the binary.
- **`list_models`** — probed, returns the full catalogue. Redundant: the model list already arrives in the
  `initialize` reply and is cached, so sending this would be a second source of truth for the same data. The
  existing decision was right; this entry exists so it is not revisited a third time.

---

## 6. Split `ClaudeSession` (carried over from the 5.0.0 static-analysis pass)

**Status:** the two remaining `config/detekt/baseline.xml` entries.

`ClaudeSession` is ~1900 lines with 46 public functions. Ten collaborators have already been extracted from it;
what remains is genuine orchestration plus the verb list the UI calls. The real fix is a split into
session-lifecycle versus UI-facing-commands — a large, behaviour-preserving refactor of the hottest file in the
repository, which belongs in its own reviewed change. The baseline file carries the full reasoning, including
why raising the thresholds instead would be worse.

---

## 7. Tighten the coverage gates when Kover allows it

`KoverVerifyRule` in Kover 0.9.2 has no per-rule filter (verified against the plugin jar), so the per-package
thresholds in `docs/RELEASE_CHECKLIST.md` §Coverage policy are enforced today as a floor plus an aggregate
rather than package by package. If a later Kover adds per-rule filters, tighten `build.gradle.kts` to match the
table that is already written there.
