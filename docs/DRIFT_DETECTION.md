# Protocol drift detection & reconciliation

The plugin speaks the `claude` binary's `stream-json`/control protocol directly. That protocol moves: the
binary auto-updates and the `@anthropic-ai/claude-agent-sdk` reference (our protocol source-of-truth) is
published independently. **Drift** = the latest SDK/binary exposes a protocol kind the plugin doesn't model.

## The detector

`./gradlew checkDrift` (on-demand, **not** wired into `check`):

1. **Updates both tools to latest first** — `npm update @anthropic-ai/claude-agent-sdk` (vendored SDK) and
   `claude --update` (the binary). The whole point is to test against current reality.
2. **Measures the surface**: extracts `subtype` literals + message-union members from the latest `sdk.d.ts`,
   and probes the updated binary (one canned turn) to capture the top-level `type`s / `subtype`s it emits.
3. **Diffs against what the plugin models** — the `KNOWN_EVENT_TYPES` / `KNOWN_SUBTYPES` sets in
   `src/test/kotlin/dev/lain/claudejb/drift/ProtocolSurface.kt` (mirrored from
   `protocol/ProtocolParser.kt`, which is where the decoder registry lives) and the recorded versions in
   `scripts/drift-baseline.properties`.
4. **Prints an agent-consumable report** and **fails** when the latest surface exposes a kind the parser
   doesn't handle (a bare version bump with a fully-covered surface passes).

Implementation: `src/test/kotlin/dev/lain/claudejb/drift/` — pure `ProtocolSurface` + `DriftDetector`
(offline unit-tested in `DriftDetectorTest`) and the `@Tag("driftLive")` `DriftLiveCheck` (the live
download + probe, run only by the `checkDrift` task, excluded from the normal `test` task).

`KNOWN_SUBTYPES` is the **full triaged surface** — every subtype the plugin is aware of, whether it
*parses* it (system subtypes, `can_use_tool`, `hook_callback`), *sends* it (host→binary control:
`initialize`, `set_model`, `get_session_cost`, `mcp_status`, …), or *deliberately rejects* it
(`request_user_dialog`, `mcp_call`, … → `UnsupportedControlRequest`). A subtype in none of these is genuinely
new and worth a human look.

## Reconciliation pipeline (run this end-to-end when checking for drift)

1. **Update** — run `./gradlew checkDrift` (updates SDK + binary, reports). It defaults to
   `~/.local/bin/claude`; pass `-PclaudeBinary=<path>` (or `CLAUDE_BINARY`) for a system-wide install.
2. **Plugin code update** — for each genuinely-new kind in the report. A **system subtype** is three
   places: its payload as a `@Serializable` class in the `protocol/*Models.kt` file for that subject, its
   case in the `ClaudeEvent` union (`protocol/ClaudeEvent.kt`), and one `typed(…)` line in the
   `SYSTEM_DECODERS` registry of `protocol/ProtocolParser.kt` — a registry rather than a `when`, so adding
   a subtype is one entry and not a branch. A **control kind** is a builder in
   `protocol/ControlProtocol.kt` when the host sends it, or a case in `ProtocolParser`'s control-request
   dispatch when the binary does. No-op if the surface is unchanged.
3. **Tests** — `./gradlew test` (full non-UI pyramid green) plus `npm test` if anything reached the UI.
4. **Update the drift detector** — extend `KNOWN_EVENT_TYPES` / `KNOWN_SUBTYPES` to cover the triaged kinds,
   and bump `scripts/drift-baseline.properties` (`sdk`, `binary`) to the updated versions. Re-run
   `./gradlew checkDrift` → green.
5. **Bump release** — `version` in `build.gradle.kts`.
6. **Code review + security review** — `/code-review` and `/security-review` over the diff.
7. **Update `.md` files** — `CHANGELOG.md`, `RELEASE_NOTES.md`, `README.md`, `CLAUDE.md`, `PROJECTMAP.md`,
   and the matrix in [`BINARY_COMPAT.md`](BINARY_COMPAT.md).
8. **Commit** — Conventional Commits (`build(protocol): re-baseline to claude X / SDK Y`), GPG-signed, and
   **no `Co-Authored-By` trailer**.
9. **Publish release** — GitFlow PRs `feature → develop → main`. The rulesets in `.github/rulesets/` decide
   how each door merges: into `main`, **a merge commit and nothing else**, because rewriting the commit is
   what strips the author's signature off the thing being published; into `develop`, squash or merge.
   Rebase is allowed on neither. **Do not tag** —
   the merge into `main` triggers `release.yml`, which cuts and signs `vX.Y.Z` itself and publishes from it.
   See [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md).
