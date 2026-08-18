# ADR 0004 — One declared shortfall against WCAG 2.2 SC 2.5.8: the subtab row's close

- **Status:** accepted
- **Date:** 2026-08-18
- **Context skill:** `accessibility-standards`

## Context

The project's conformance target is **WCAG 2.2 Level AA**. One control in the chat UI does not meet it, and
this record exists so that it is a decision with a boundary and a way out rather than a defect nobody
remembers taking.

**The criterion.** SC 2.5.8 Target Size (Minimum), Level AA, requires that *"the size of the target for
pointer inputs is at least 24 by 24 CSS pixels"*. Five exceptions are listed: **spacing** (a 24 px circle
centred on the target intersects no other target's circle), an **equivalent** control elsewhere on the page,
an **inline** target inside a sentence, a **user-agent** control, and a presentation that is **essential**.

**The control.** The tab bar has two rows. The upper one is the chats; the lower one holds every agent,
subagent and background task the open chat started. Each pill carries a close (`.pill-x`) as its sibling. On
the chats' row that control is **24 × 24**. On the subtab row it is **20 × 20**, and none of the five
exceptions applies: it sits directly against its pill, so the spacing exception fails — the 24 px circles
centred on the two intersect — and there is no equivalent control elsewhere.

**Why it is that size.** The subtab pill is **21 px** tall. A 24 px control does not fit inside it, so the row
grows by three pixels the moment a subtab is opened. That row is directly above the transcript, so every
navigation between the chat and one of its agents would shift the conversation under the reader. The row is
also the one that holds dozens of pills in a tool window a few hundred pixels wide, which is what fixes its
height in the first place: it is deliberately a size below the chats' row, because the chats are the
navigation and the subtabs change under you while a turn runs.

So the two failures available here are not equivalent. One is a target three quarters of the required area;
the other is a layout that moves while it is being read, which is a worse experience for exactly the same
users and is not obviously conformant either.

## Decision

**Keep the subtab row's close at 20 × 20, as a declared, scoped and gated exception.** Close the gap wherever
the pill has room — it is closed on the chats' row — and never let a second control join it.

Three things make that a decision rather than a shrug:

1. **It is registered where it gets audited.** There is no accessibility statement or conformance report in
   this repository, so the register is `src/test/frontend/accessibility.test.js`, which is the only
   accessibility record that runs. The declaration sits beside the rule in `css/tabs.css` as well, but a
   comment beside the cause is not a register: nobody reads it again.
2. **It is asserted in BOTH directions.** A declared exception fails in two ways and only one of them looks
   like a failure. Someone shrinks the control further — caught, because the size is pinned at 20. Or the
   constraint that forced it goes away and nobody notices the exception could be retired — caught, because
   the 24 × 24 on the chats' row is pinned too, and its failure message is what prompts the retirement.
3. **It covers exactly one class.** The gate asserts the SIZE of the exception, not merely its existence: it
   fails if any other glyph appears in that rule. Two other controls shared this box — the `⋮` that opened
   the agent tree and the `⇱` that pinned a subtab as a tab of its own — and both went with the features
   behind them. A new glyph added there would inherit a documented shortfall that nobody decided to accept.

## Why the deviation is justified, and what bounds the harm

- **One control, one row.** It is not a pattern applied across the UI; it is a single class scoped to
  `.subtab-capsule`.
- **It is only ever on the subtab you are already reading.** Reaching it means the pointer is already in that
  row.
- **Missing it costs nothing.** The control hides a transcript view. It destroys no work, sends nothing and
  cannot be confused with a destructive action; a mis-click is undone by clicking the pill again.
- **Nothing else about the control is degraded.** It is a real `<button>` with an accessible name, it is in
  the tab order, and it has the same visible focus indicator as everything else on the page (SC 2.4.7,
  1.4.11).

## Trigger to retire it

Any **one** of these closes the exception, and the work is scheduled rather than argued about again:

1. **The subtab row is given the chats' row height** — the 21 px pill is the whole constraint, and the moment
   it is not there is no reason left.
2. **The close moves out of the pill's flow** (a context action, a keyboard-only affordance with its own
   target, a control that overlays rather than sits beside), so its box stops being bounded by the row.
3. **A conformance obligation makes the shortfall unacceptable at any cost** — a published accessibility
   statement, a VPAT/ACR asked for by a buyer, or a market whose regulator names 2.5.8 specifically. A
   declared exception in a test file is not a statement to a third party, and the moment one is made, this
   becomes a defect with a bug number.

## Consequences

- The gate in `accessibility.test.js` is part of this decision, not documentation of it. Weakening either
  assertion to make an unrelated change pass reopens the ADR; it does not settle it.
- New tab-bar glyphs are 24 × 24 by default. Anything smaller is a new decision and needs its own record —
  this one does not extend by analogy.
- This ADR is reviewed at each major release. The honest outcome of a review is either "retired, the row is a
  size up now" or "still true, and here is the row that still cannot hold 24 px".
