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
   `src/test/.../drift/ProtocolSurface.kt` (mirrored from `protocol/ClaudeEvent.kt`) and the recorded
   versions in `scripts/drift-baseline.properties`.
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

1. **Update** — run `./gradlew checkDrift` (updates SDK + binary, reports).
2. **Plugin code update** — for each genuinely-new kind in the report, add the serializer/`when` branch in
   `protocol/ClaudeEvent.kt` (event/system subtype) or `protocol/ControlProtocol.kt` (control kind). No-op if
   the surface is unchanged.
3. **Tests** — `./gradlew test` (full non-UI pyramid green).
4. **Update the drift detector** — extend `KNOWN_EVENT_TYPES` / `KNOWN_SUBTYPES` to cover the triaged kinds,
   and bump `scripts/drift-baseline.properties` (`sdk`, `binary`) to the updated versions. Re-run
   `./gradlew checkDrift` → green.
5. **Bump release** — `version` in `build.gradle.kts`.
6. **Code review + security review** — `/code-review` and `/security-review` over the diff.
7. **Update `.md` files** — `CHANGELOG.md`, `RELEASE_NOTES.md`, `README.md`, `CLAUDE.md` (version refs).
8. **Commit** — GP-signed commits, no `Co-Authored-By: Claude` trailer.
9. **Publish release** — GitFlow PRs `feature → develop → main` (admin rebase-merge), normalize branches,
   signed `vX.Y.Z` tag, GitHub release with the built zip.
