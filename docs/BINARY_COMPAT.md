# Binary compatibility

Two ranges have to hold at once, and they move independently:

- the **`claude` binary / Agent SDK** whose `stream-json` + control protocol the plugin speaks;
- the **IntelliJ Platform** builds the plugin loads into.

This document records both, and what to do when either moves.

## Current state

| | Value | Where it is declared |
|---|---|---|
| Protocol baseline | `claude` **2.1.226** / SDK **0.3.231** | `scripts/drift-baseline.properties` |
| IDE range | **253.29346.138 → 263.\*** (2025.3.**1** → the 2026.3 branch) | `build.gradle.kts` → `ideaVersion` |
| Compiled against | IDEA `253.29346.138` — the floor itself | `build.gradle.kts` → `intellijIdea("253.29346.138") { useInstaller = false }` |
| Verified against | the recommended range **plus** the newest IDEA **and PyCharm** EAP/RC | `pluginVerification.ides` |

**There is no enforced minimum binary version.** The plugin does not probe for one and would not refuse an
older `claude`; the baseline above is the version the protocol layer was last *reconciled* against, which is
a different claim. An older binary is simply untested — it will typically work, because everything the plugin
sends is long-established, and it will silently omit whatever it does not implement.

**The IDE floor is JCEF, not an API tidy-up — and it is a BUILD, not a branch.** The entire UI *is* the
embedded browser, so `plugin.xml` declares `com.intellij.modules.jcef` as a mandatory dependency; without it
the classloader hands the plugin no `com.intellij.ui.jcef.*` and every chat dies in `JcefHost.<init>`. That
module id is absent from 2025.1 and 2025.2 altogether, and it is still absent from the first 2025.3
(`253.28294.334`) — it arrives in **`253.29346.138` (2025.3.1)**, which is therefore the floor and is written
as that full build number everywhere it is declared. A `sinceBuild` of the bare branch `253` would offer the
plugin to `253.28294.334`, where the platform refuses to load it (`has dependency on
'com.intellij.modules.jcef' which is not installed`). The dependency cannot be softened either: an optional
dependency that cannot be satisfied is skipped, which trades a clean refusal for a `NoClassDefFoundError`.

`JcefDependencyContractTest` is the gate. It fails if the sources use JCEF and the descriptor stops declaring
it, if the declaration becomes optional, if `sinceBuild` is a branch rather than a full build number, or if
it is below `253.29346.138`. `verifyPlugin` does **not** catch any of it — it resolves against the whole IDE
distribution rather than against the plugin's classloader, which is where the failure lives, so it can report
*Compatible* on an IDE the plugin cannot start on.

## Protocol version history

Each row is the baseline a release was reconciled at, i.e. the point where `./gradlew checkDrift` was green
and `ProtocolSurface` covered the surface both the SDK types and a live probe exposed.

| Plugin version | `claude` binary | SDK ref | Notes |
|---|---|---|---|
| 5.5.0 | 2.1.226 | 0.3.231 | Current. Surface unchanged; the release's protocol work was reading the subagent sidecars the binary already writes. |
| 5.0.0 | 2.1.222 | 0.3.222 | `checkDrift` green across the move of the SDK to `devDependencies`; surface unchanged. |
| 4.3.3 | 2.1.220 | 0.3.220 | Surface unchanged. |
| 4.2.0 | 2.1.204 | 0.3.204 | Five new kinds reconciled, `background_tasks_changed` and `control_request_progress` among them. |
| 4.0.4 | 2.1.193 | 0.3.193 | Added `informational`, `model_refusal_no_fallback`, `worker_shutting_down`. |
| 4.0.1 | 2.1.170 | 0.3.170 | Added `model_refusal_fallback`; triaged `get_usage`, `register_repo_root`, `reload_skills`. |

Releases before 4.0.1 predate the drift detector. This document previously recorded 2.0.1 as tested against
`claude` 2.1.150 and 2.2.0 against 2.1.161; those figures are kept only as history — nothing re-verifies them.

## How new protocol kinds are absorbed

`ProtocolParser` decodes leniently (`ignoreUnknownKeys = true`), so a newer binary adding **fields** to an
existing message cannot break an older plugin: the field is dropped and nothing renders it. An entirely new
**message or control kind** needs code — a typed case in `protocol/ClaudeEvent.kt` (receive) or a builder in
`protocol/ControlProtocol.kt` (send), and then whatever surfaces it in `session/` and `ui/jcef/`.

The plugin's own view of the surface lives in **one** set, deliberately: `KNOWN_SUBTYPES` in
`src/test/kotlin/dev/lain/claudejb/drift/ProtocolSurface.kt` is the *full triaged* list — everything the
plugin parses, answers, sends, or knowingly declines to send (`list_models`, `get_plan`,
`get_workspace_diff`, which belong to the remote thin client rather than to us). A subtype outside that set
is genuinely new and needs a human decision, which is exactly what the detector reports.

## When the binary or the SDK moves

`./gradlew checkDrift` updates both tools to latest, probes the real binary, and diffs the resulting surface
against the sets above. It is **on-demand**, not part of `check`, and `drift.yml` runs it weekly and **files
an issue** rather than committing — deciding whether a new kind is modelled or ignored is a judgement call.
The full reconciliation sequence is in [`DRIFT_DETECTION.md`](DRIFT_DETECTION.md); the short version:

1. `./gradlew checkDrift` — read the report. On this machine the binary is a system-wide install, so it needs
   `-PclaudeBinary=/usr/bin/claude`; the task otherwise defaults to `~/.local/bin/claude`.
2. Model each genuinely-new kind, or record the decision not to.
3. `./gradlew test` green.
4. Extend `KNOWN_EVENT_TYPES` / `KNOWN_SUBTYPES` and bump `scripts/drift-baseline.properties`.
5. Add a row to the table above, and a `CHANGELOG.md` entry.
