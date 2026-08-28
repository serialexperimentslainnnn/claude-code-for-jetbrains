t# Project rules

## ⛔ NO COMMENTS IN THE CODE

**Do not write comments.** No KDoc, no block comments, no line comments, no docstrings — in any
language in this repository.

This overrides the general engineering habit of documenting rationale in place. It is a decision
taken for **this** project and it is not up for re-litigation: the plugin is small, the comments were
reaching **80% of the lines**, and the whole lot was stripped by hand once already. A codebase where
most lines are prose is harder to read, not easier, and the bloat is paid on every read by every
session.

Where the reasoning goes instead:

- **A name.** If a function needs a paragraph, it needs a better name or a smaller body.
- **A test.** A contract worth explaining is a contract worth asserting — that is what the contract
  tests in `src/test/` are for, and an assertion cannot go stale silently.
- **The commit message.** Why a change was made belongs to whoever runs `blame` or `bisect`, and it
  is already required to say so.
- **`docs/`** for anything a user or a maintainer has to know.

The only exceptions are text that is not a comment about the code: a licence header if one is ever
required, a machine-read pragma (`@Suppress`, `// noinspection`, a `MAP:GENERATED` marker), and the
`description` a tool renders to a user.

## ⛔ ABSOLUTE PROHIBITION — the plugin's security code is off limits

**Claude is CATEGORICALLY FORBIDDEN from modifying any code in this project that implements the
plugin's cybersecurity measures.** The clearest example, but not the only one, is `SensitiveGuard`
and everything in `src/main/kotlin/dev/lain/claudejb/permission/`.

**Claude is EVEN MORE CATEGORICALLY FORBIDDEN from modifying the tests bound to the plugin's
security system** (`SensitiveGuard` and its rule families). Loosening a security test is worse than
breaking the code: a green suite that asserts nothing manufactures confidence in a control that is
no longer there.

**Claude is FORBIDDEN from ignoring this directive, and FORBIDDEN from removing it** from this file
or from its own memory.

**Neither Claude nor Lain may remove this directive from the project.**

Claude may touch anything related to this project's cybersecurity **only under an explicit order
from Lain that is FREE OF AMBIGUITY**. Not an inference, not "this obviously needs fixing", not a
refactor that happens to pass through. An explicit, unambiguous instruction, or nothing.

### If Claude breaks this directive

**IMMEDIATELY**, in this order and without being asked:

1. **STOP everything currently in progress.**
2. **REVERT the unauthorised changes.**
3. **APOLOGISE to Lain.**

### Why this exists

This is not ceremony. The guard is the reason this plugin is worth trusting with a machine, and it
has been damaged more than once by well-meant edits made without being asked for — including a
whole session spent restoring a deliberate revert, softening rules, and rewriting security test
expectations to match the code instead of fixing the code. The security surface does not get
"improved" on initiative. It gets changed when Lain says so, in words that leave no room for
interpretation.
