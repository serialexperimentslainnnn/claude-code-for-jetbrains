# ADR 0003 — Internationalisation is deferred, deliberately

- **Status:** accepted
- **Date:** 2026-08-05
- **Context skill:** `i18n-standards`

## Context

The plugin ships **no** internationalisation infrastructure, and this is a decision rather than an oversight —
which is precisely the distinction an ADR exists to record. Verified state of the repository, not an
impression:

- no `.properties` resource bundle anywhere under `src/main/resources`;
- no `resource-bundle` element in `plugin.xml`;
- no `DynamicBundle` / `ResourceBundle` usage and no `@Nls` annotations in the Kotlin;
- user-facing text sits inline at its call site, in English, in both the Kotlin (notifications, dialogs,
  Settings labels) and the JCEF web modules.

The standard's default is that user-facing strings are externalised into a catalogue from the start, because
retrofitting one is far more expensive than starting with it. That default is correct, and the repository
does not follow it.

## Decision

**Defer i18n. Do not externalise strings in 5.0.0.** Ship English-only, and record the trigger that reverses
this decision rather than leaving it to be rediscovered.

## Why the deviation is justified

**The audience is already working in English.** The product is a developer tool whose entire subject matter —
the `claude` binary's slash commands, its tool names, its error strings, the model's own output — arrives in
English and is not ours to translate. A Spanish UI wrapped around an English transcript is not a localised
product; it is an inconsistent one.

**There is no demand.** Across the Marketplace listing's install base there has been no request for another
language. i18n is not free: a catalogue is a second artifact that must be kept in sync, and a stale
translation is worse than no translation — it lies about what a button does. Paying that cost against zero
demand is the sort of speculative generality the project's own KISS principle rejects.

**The retrofit cost is bounded and known.** The volume is on the order of a hundred strings across roughly
eight Kotlin files and the JCEF modules — a day's mechanical work, not a rewrite. This is the specific reason
the usual "externalise early or never" argument does not bind here: the codebase is small enough that the
migration stays cheap, so deferring does not quietly become deciding.

## What this ADR does **not** excuse

Two things are often filed under i18n and are **not** deferred, because they are accessibility criteria and
one of them is a legal obligation in the plugin's distribution market:

- **`lang` on the document is declared** (`shell.html`). Without it a screen reader pronounces the interface
  with the wrong phonetics — WCAG 3.1.1 Language of Page, Level **A**, and among the six most common failures
  on the web. It is pinned by a test in `src/test/frontend/accessibility.test.js`.
- **Layout must tolerate text it did not author.** Model output, file paths and tool names are arbitrary
  length and arbitrary script; the transcript and composer wrap and scroll rather than assuming English-width
  content. That is a robustness property, and it happens to be most of what makes a later translation
  survivable.

## Trigger to revisit

Any **one** of these reopens the decision, and the work is scheduled rather than argued about again:

1. A real request for a specific language from a Marketplace user or a contributor offering the translation.
2. Distribution into a market or an organisation that requires a localised interface contractually.
3. The string count outgrowing "a day's mechanical work" — at which point deferring *has* become deciding,
   and the cheap moment has passed.

## Consequences

- New user-facing strings continue to be written inline in English. No half-measure bundle that covers a
  quarter of the UI: a partial catalogue has all of the maintenance cost and none of the benefit.
- If trigger 1 fires, the first step is the bundle plus `DynamicBundle`, **then** the translation — never a
  translation grafted onto inline strings.
- This ADR is reviewed at each major release. If it is still "no demand" three majors from now, that is
  itself the answer.
