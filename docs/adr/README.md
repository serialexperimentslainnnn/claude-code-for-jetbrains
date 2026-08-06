# Architecture Decision Records

Decisions that were **hard to reverse, or easy to reverse by accident**. Everything else belongs in the code
and its comments, where it stays honest; a document that merely restates the code goes stale and then lies.

What earns a record here:

- a **one-way door** — a choice that would cost weeks to undo (the branching model, the signing story);
- a **deliberate deviation** from a standard the project otherwise follows, so the next reader finds the
  reasoning instead of assuming an oversight and "fixing" it;
- a **deferral**, with the trigger that reverses it written down — otherwise deferring silently becomes
  deciding.

## Index

| ADR | Title | Status | What it settles |
|---|---|---|---|
| [0001](0001-release-process.md) | Release process: branching, signing and tag immutability | accepted | Why GitFlow rather than trunk-based, why GPG-on-YubiKey rather than SSH signing, and why a published tag is never moved |
| [0002](0002-threat-model.md) | Threat model: what the plugin defends against, and what it does not | accepted | The trust model and STRIDE over the three real surfaces; why prompt injection is assumed to succeed rather than detected; the non-goals |
| [0003](0003-i18n-deferred.md) | Internationalisation is deferred, deliberately | accepted | Why there is no resource bundle, what that does **not** excuse (WCAG 3.1.1), and the three triggers that reopen it |

## Format

Markdown, numbered `NNNN-kebab-title.md`, never renumbered. Front matter is status, date, and the standards
skill the decision was reasoned against. A superseded ADR is **not deleted**: its status becomes
`superseded by NNNN` and it stays, because the reasoning that was later overturned is part of the record.

Status values in use: `proposed`, `accepted`, `superseded by NNNN`, `deprecated`.
