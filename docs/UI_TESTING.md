# UI testing (RemoteRobot, Layer D)

The end-to-end UI tests in `src/uiTest/` are **RemoteRobot clients**: they do not spawn an IDE, they talk to
an already-running IDE over HTTP and drive its Swing UI. This is the top of the test pyramid (unit → headless
→ fake-claude integration → **UI e2e**). They are gated by `-PuiTest.enabled=true` and run nightly / on demand,
never as part of `check`.

## Moving parts

| Piece | Where | Role |
|-------|-------|------|
| `runIdeForUiTests` | `build.gradle.kts` (`intellijPlatformTesting.runIde`) | Boots an IDE-under-test with the `robot-server` plugin on `:8082`, plugin loaded, pointed at `bin/fake-claude`. |
| `uiTest` (Test task) | `build.gradle.kts` (`tasks`) | The JUnit5 client suite (`src/uiTest`); connects to `:8082`. Gated by `-PuiTest.enabled=true`. |
| `UiTestBase` | `src/uiTest/.../ui/UiTestBase.kt` | `RemoteRobot` client + helpers (open tool window, find composer/transcript, send prompt, assert). |
| `bin/fake-claude` | repo root | Deterministic `claude` stand-in; replays a JSONL fixture chosen by `FAKE_FIXTURE`. |

## Fake binary + fixture injection (automatic)

`runIdeForUiTests` launches the IDE with two system properties:

```
-Dclaudejb.fakeClaude=<repo>/bin/fake-claude
-Dclaudejb.fakeFixture=<repo>/src/test/resources/fixtures/multi_message.jsonl
```

`ClaudeSettings` reads them **only when present** (a no-op in a shipped IDE, where they are unset):

- `claudePath` falls back to `claudejb.fakeClaude` when the persisted path is blank.
- `resolveEnv()` adds `FAKE_FIXTURE=<claudejb.fakeFixture>` unless the user already set `FAKE_FIXTURE`
  explicitly in the settings env vars.

So the plugin drives the fake binary with a deterministic, network-free scenario without any manual Settings
edit or sandbox pre-seeding.

**Per-scenario fixtures:** to exercise a different scenario, relaunch the IDE with a different fixture, e.g.

```
./gradlew runIdeForUiTests -Dorg.gradle.jvmargs= \
  # then override the property by editing the task default, or pass it through your own run wrapper
```

The simplest path is to point the build default at the fixture you want, or add a dedicated `runIde.register(...)`
variant per scenario. Available fixtures live in `src/test/resources/fixtures/` (`multi_message.jsonl`,
`thinking_turn.jsonl`, `tool_use_permission.jsonl`, `rate_limit.jsonl`, `interrupt_turn.jsonl`, …). They replay
autonomously (paced with `_sleep_ms`, no stdin gating), so they work both for the headless integration tests
and for these UI tests.

## Running locally (with a display)

Two steps, in order — the IDE must be **up** before the client suite connects:

```bash
export JAVA_HOME=~/.local/jdks/jdk-21.0.11+10

# Terminal 1: boot the IDE-under-test (keep it running). robot-server listens on :8082.
./gradlew runIdeForUiTests

# Terminal 2: once the IDE window has finished loading, run the client suite.
./gradlew uiTest -PuiTest.enabled=true
```

## Running headless (CI runner without a display)

Wrap the IDE launch in `xvfb-run` (or start an `Xvfb` on a `$DISPLAY` and export it). Example:

```bash
export JAVA_HOME=~/.local/jdks/jdk-21.0.11+10

# Boot the IDE under a virtual framebuffer, in the background.
xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runIdeForUiTests &
IDE_PID=$!

# Wait for robot-server to answer before starting the client suite.
for i in $(seq 1 60); do
  curl -sf http://127.0.0.1:8082 >/dev/null 2>&1 && break
  sleep 2
done

./gradlew uiTest -PuiTest.enabled=true
ST=$?

kill "$IDE_PID" 2>/dev/null
exit $ST
```

Notes:
- This repo's **real CI is GitLab self-hosted** (`.gitlab-ci.yml`); add the above as a manual / scheduled job
  on a runner that has `xvfb` and a display-capable image. The `.github/workflows/ui-tests.yml` is inert
  reference (Actions capped by billing).
- Override the endpoint with `-Drobot-server.url=http://<host>:<port>` (forwarded to the `uiTest` task) when
  the IDE runs on a different machine.
- `runIdeForUiTests` also disables the privacy/consent dialogs and startup tips so the first run is clean
  (`-Djb.consents.confirmation.enabled=false`, `-Dide.show.tips.on.startup.default.value=false`, etc.).

## Writing a per-feature UI test

Subclass `UiTestBase`, open the tool window, drive the composer, assert on the transcript:

```kotlin
class MyFeatureUiTest : UiTestBase() {
    @Test fun `does the thing`() {
        val tw = openClaudeToolWindow()
        sendPrompt("hola", tw)
        waitForTranscript("expected a reply") { it.contains("First part", ignoreCase = true) }
    }
}
```

`ChatSmokeUiTest` is the minimal template. Locators in `UiTestBase` are commented with `// inspector:` hints:
open the **UI Robot inspector** (bundled with `robot-server`) against the running IDE to tighten each XPath
when a widget gains a stable accessible name.
