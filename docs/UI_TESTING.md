# UI testing (RemoteRobot, Layer D)

The end-to-end UI tests in `src/uiTest/` are **RemoteRobot clients**: they do not spawn an IDE, they talk to
an already-running IDE over HTTP and drive it. This is the top of the test pyramid (unit → headless →
fake-claude integration → **frontend/vitest** → **UI e2e**). They are gated by `-PuiTest.enabled=true` and run
nightly / on demand, **never** as part of `check`.

## Where the UI actually is

Since **4.0.0** the chat is an embedded Chromium web app (JCEF), and since **5.5.0** so is the tab bar. The
Swing chat UI the first version of this suite drove — `ChatPanel`, `TranscriptView`, the composer `JBTextArea`,
the tray/strip panels, and later the two Swing tab strips — **does not exist any more**. A JCEF browser paints
one image, not Swing components with strings in them, so `findAllText()` over the tool window returns nothing
about the transcript, the composer or the tabs.

So the suite works on two layers:

| Layer | Reached with | What is there |
|-------|--------------|---------------|
| **Swing** | plain RemoteRobot XPath | the tool-window stripe button, `ChatTabsPanel` (draws nothing, owns the chats), the title actions and the gear menu, the Settings dialog, IDE notifications, editor tabs, native diff viewers |
| **DOM** | JetBrains' `JCefBrowserFixture` via `UiTestBase.web()` / `js()` / `findDom()` | everything else — transcript, composer, cards, dashboard, tab bar |

`JCefBrowserFixture` injects a `JBCefJSQuery` into the page and evaluates JavaScript through CEF's host API,
which is **not** subject to the page CSP (the same reason `JcefHost.exec` works against a hash-pinned
`script-src`). Assertions are therefore made against the real DOM, in the real browser, **with real layout** —
which is exactly what the jsdom frontend suite (`npm test`) cannot check.

## Moving parts

| Piece | Where | Role |
|-------|-------|------|
| `runIdeForUiTests` | `build.gradle.kts` (`intellijPlatformTesting.runIde`) | Boots an IDE-under-test with the `robot-server` plugin on `:8082`, this plugin loaded, pointed at `bin/fake-claude`, with the JCEF JS-query pool pre-reserved. |
| `uiTest` (Test task) | `build.gradle.kts` (`tasks`) | The JUnit5 client suite (`src/uiTest`); connects to `:8082`. Gated by `-PuiTest.enabled=true`. |
| `UiTestBase` | `src/uiTest/.../ui/UiTestBase.kt` | The harness: tool window, gear/title actions, popup items, the browser fixture, `js`/`findDom`, `newChat`, `awaitChatPage`. |
| `bin/fake-claude` | repo root | Deterministic `claude` stand-in: replays a JSONL fixture, and answers `auth status` so a session can start at all. |

### The two preconditions, and how they are met

1. **`-Dide.browser.jcef.jsQueryPoolSize=10000`** on the IDE-under-test's command line. It is a platform
   *registry* key (`JBCefClient` reads it into `JS_QUERY_POOL_DEFAULT_SIZE`; a registry value falls back to the
   system property of the same name), and a `JBCefJSQuery` can only be attached to an **already-loaded**
   browser if the slot was reserved when the client was created. Without it every DOM-driving test dies at
   fixture construction — loudly ("Set the property `JBCefClient.Properties.JS_QUERY_POOL_SIZE` …"), never
   silently green. `runIdeForUiTests` passes it; if you launch the IDE some other way, **you must pass it too**.
2. **An identity.** `ClaudeSession.start()` refuses to launch without a credential (`AuthGate.hasCredential`),
   and with nothing in the IDE password safe that question ends at `claude auth status`. `bin/fake-claude`
   answers it (see below), so a clean machine gets a session instead of the sign-in card.

## Fake binary, fixture and identity (all automatic)

`runIdeForUiTests` launches the IDE with:

```
-Dclaudejb.fakeClaude=<repo>/bin/fake-claude
-Dclaudejb.fakeFixture=<repo>/src/test/resources/fixtures/multi_message.jsonl
```

`ClaudeSettings` / `SettingsLaunchEnv` read them **only when present** (a no-op in a shipped IDE):

- `claudePath` falls back to `claudejb.fakeClaude` when the persisted path is blank.
- `resolveEnv()` adds `FAKE_FIXTURE=<claudejb.fakeFixture>` unless the user set `FAKE_FIXTURE` explicitly.

`bin/fake-claude` then behaves as two different programs, keyed on its **first argument**:

- `auth status` → one JSON object, exit 0, in the shape `AuthCli.AuthState` models:
  `{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"not-a-real-account@fake-claude.invalid",…}`.
  The identity is deliberately impossible (`.invalid` is the RFC 2606 reserved TLD) and carries **no token and
  no key** — `auth status` describes an identity, it never hands one over. If you see that address in a
  dashboard, you are looking at the test double.
- `auth login` → fails, exit 1, on purpose: a stand-in mints no credentials. It exists so
  `CredentialsVault.renew` gets a fast, honest "no" instead of a stream-json fixture replayed as the answer to
  a login.
- anything else (i.e. `--print …`) → the stream-json fixture replay, **byte-identical** to what it has always
  been. The streaming invocation always begins with `--print`, so the two paths cannot collide.

**Per-scenario fixtures:** point the task's `claudejb.fakeFixture` default at another file under
`src/test/resources/fixtures/` (`multi_message.jsonl`, `thinking_turn.jsonl`, `tool_use_permission.jsonl`,
`rate_limit.jsonl`, `interrupt_turn.jsonl`, …), or register a second `runIde` variant beside
`runIdeForUiTests`. There is no per-test switch: the fixture is chosen when the IDE boots.

## Running locally (with a display)

Two steps, in order — the IDE must be **up** before the client suite connects:

```bash
export JAVA_HOME=~/.jdks/jbr-21.0.11

# Terminal 1: boot the IDE-under-test (keep it running). robot-server listens on :8082.
./gradlew runIdeForUiTests

# Terminal 2: once the IDE window has finished loading, run the client suite.
./gradlew uiTest -PuiTest.enabled=true
```

## Running headless (CI runner without a display)

Wrap the IDE launch in `xvfb-run` (or start an `Xvfb` on a `$DISPLAY` and export it):

```bash
export JAVA_HOME=~/.jdks/jbr-21.0.11

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
- CI runs on **GitHub Actions**, and this suite is deliberately **not** part of the gate: it needs a display,
  it is slower than everything else combined, and a flaky required check teaches people to re-run until green.
  Add it as a scheduled or `workflow_dispatch` workflow on a runner with `xvfb` if you want it automated —
  never as a required status check.
- Override the endpoint with `-Drobot-server.url=http://<host>:<port>` (forwarded to the `uiTest` task) when
  the IDE runs on another machine.
- `runIdeForUiTests` also disables the privacy/consent dialogs, tips and the trust prompt, and opens the tiny
  `src/uiTest/resources/sandbox-project` so the IDE is never sitting on the welcome screen.

## The tests, and what each one actually proves

| Test | Proves |
|------|--------|
| `ChatSmokeUiTest` | The tool window gives a **live web view**, not the "needs JCEF" Swing fallback: `#conversation` and the composer textarea exist, the bar draws ≥1 chat and marks exactly one with `aria-current`. This is the cheap guard on the failure the 253 floor exists for (no `com.intellij.modules.jcef` ⇒ `NoClassDefFoundError` in `JcefHost.<init>`). |
| `ComposerUiTest` | Keystrokes from the **OS keyboard** reach the page (the focus bug that made a new tab unusable for a whole release), and Enter sends while Shift+Enter keeps a multi-line draft. |
| `NewChatTabUiTest` | The whole 5.5.0 tab round trip: a Swing action builds a panel, `ChatTabsPanel` adds a `CardLayout` card and pushes the chat list into **every** open page, a pill click comes back as a `selectChat` bridge message, the strip swaps the card, both pages repaint with the selection moved. |
| `TabBarScrollUiTest` | The chat row scrolls by wheel (Chromium will not move a horizontal scroller with a vertical wheel — `app-tabs.js` translates the gesture) and by grabbing it. **Overflow is a layout fact**, so jsdom cannot answer this: there `scrollWidth`/`clientWidth`/`scrollLeft` are all 0. |
| `BootScreenUiTest` | The waiting screens (`#boot`, `#auth-card`) live inside `#work`, below `#tabsbar` — asserted twice, by geometry *and* by hit-testing the centre of a chat pill, because "does not cover" has two failure modes. |
| `SessionDashboardUiTest` | The gear's "Session Info" opens the **JCEF dashboard** (not the deleted Swing dialogs), the transcript hides while it is up, "Chat" gives it back — and the view buttons are children of the tab bar that intersect no pill (**WCAG 2.2 SC 2.4.11, Focus Not Obscured**). |
| `AttachmentChipUiTest` | Host → page → host: "Add Current File" pins a chip in the composer and the chip's ✕ comes back as a `removeAttachment` bridge message the host honours. Neither half is testable alone (jsdom has no host, headless has no browser). |
| `SettingsPageUiTest` | The gear's "Settings…" opens *our* page with its launch options, the Model combo is bound to its label, and the Effort combo lists the `EffortLevel` wire values exactly. Cancels without applying — the dialog writes to the password safe. |
| `OpenPreviousSessionUiTest` | "Open Previous Session…" answers **something**: the chooser, or the honest "No previous sessions" dialog. Which one depends on the machine (history is the binary's own files); the failure it catches is an action that opens nothing, which is a real risk given the two `invokeLater` hops behind it. |

## Two escaping rules the harness enforces

They are opposites, and both are checked rather than left to bite you as a timeout:

1. **`js(...)` snippets: double quotes, no backslashes, one line.** The expression is embedded in a
   *single-quoted, single-line* Nashorn string on the IDE side. A single quote closes it; a backslash is
   consumed in transit, so an escaped quote arrives unescaped and the page gets a syntax error; a newline ends
   it. `UiTestBase.js` rejects all three with a message that names the cause. Write
   `(function () { … })()` one-liners with double quotes, returning a string. (Corollary seen in
   `ChatSmokeUiTest`: attribute selectors go unquoted — `[aria-current=true]` — because CSS allows a bare
   identifier there and nothing is lost.)
2. **`findDom(...)` XPath: single quotes.** The fixture escapes `'` to `\x27`, which survives the Nashorn
   string and arrives at the page as a quote again; a double quote is escaped the same way and then lands
   *inside* the double-quoted JS call carrying it, breaking the expression.

## What this suite cannot cover, and why

Read this before adding a test — the gaps are structural, not "not written yet".

- **Anything that needs a live turn.** The fake binary replays its fixture **autonomously at spawn**, not in
  response to what you type: the reply is not correlated with the prompt, and it is consumed once. So tool
  cards, permission/question/elicitation cards, the review diff and its hunks, rewind, streaming/thinking
  rows, model-refusal notices and quota bars are all out of reach here — a test asserting them would be
  asserting the fixture, not the product. They are covered by the **integration** tests (a real `ClaudeSession`
  against `bin/fake-claude` with a scenario fixture) and by the **vitest** suite over the real shipped JS.
- **Agent tabs and the agent tree.** `AgentRegistry` reads sidecar files the binary writes —
  `<sessionId>/subagents/agent-<id>.{jsonl,meta.json}` — plus the admission trail in `PluginAgentIndex`. The
  stand-in writes none of them, so no agent tab can ever appear. Faking them would mean writing the binary's
  private on-disk layout from the test harness, i.e. pinning our guess at the format instead of the format.
- **Background tasks.** Same root cause: the plugin's own record is built from `tool_result` /
  `task_notification` events of a real turn, and the output it tails is a real file under
  `/tmp/claude-<uid>/…/tasks/<taskId>.output`.
- **The model catalogue.** It arrives in the `initialize` reply, so on this harness the model combo and the
  composer's model pill are legitimately empty — which is why `SettingsPageUiTest` asserts the Effort enum
  exactly and only checks that the Model combo *exists*. Labels are pinned in the unit suite
  (`JcefModelLabelTest`).
- **Session history content.** It comes from `~/.claude/projects/…`, i.e. from the machine, which is why
  `OpenPreviousSessionUiTest` accepts either outcome and picks nothing.
- **Multiple scenarios in one run.** The fixture is fixed when the IDE boots; there is no per-test switch.

## Sharp edge: the sandbox IDE shares your OS keyring

**Running this suite on a developer machine will move `~/.claude/.credentials.json` into the password safe and
delete it.** That is *normal plugin behaviour*, not something the tests do — but it surprises everybody exactly
once, so it is written down here.

Why: the IDE's `PasswordSafe` is backed by the OS store (Secret Service/KWallet on Linux, Keychain on macOS,
Credential Manager on Windows), and the entry name (`generateServiceName("Claude Code", …)`) is not
per-instance — the sandbox IDE and your real IDE read and write the **same** entries. The sandbox is not in
unit-test mode, so `CredentialsVault` is fully live there: the first `refreshBootState()` poll runs
`AuthGate.absorbExistingLoginOnce()` → `CredentialsVault.harvest()`, which reads the binary's plaintext
credentials file, files it in the safe (verified) and then overwrites and unlinks it.

What that means in practice:

- **Your login is not lost.** It is in the safe, and your real IDE keeps using it.
- **Your terminal `claude` loses its file** and will ask you to sign in again. Re-run `claude auth login` when
  you need the CLI; the plugin will simply harvest that one too, by design.
- **`SecretStore.AUTH_STATUS` may end up holding the stand-in's identity** (`auth status` is filed whenever the
  reply names an account), so your real IDE's dashboard can show `not-a-real-account@fake-claude.invalid`
  until the next real probe overwrites it. That is precisely why the fake identity is obviously fake: the
  symptom names its own cause.
- If none of that is acceptable on your box, run the suite on a throwaway machine, in a container, or under a
  separate `$HOME`.

## Writing a new UI test

Subclass `UiTestBase`, open the tool window, wait for the page, then assert against the DOM:

```kotlin
class MyFeatureUiTest : UiTestBase() {
    @Test fun `does the thing`() {
        openClaudeToolWindow()
        awaitChatPage()
        waitForWeb(
            "the thing to appear",
            "(function () { return String(!!document.querySelector(\".thing\")); })()",
        )
    }
}
```

`ChatSmokeUiTest` is the minimal template. Keep every assertion true **whether or not a turn can run** (see
above), prefer a DOM fact over painted text, and when a Swing locator is unavoidable open the **UI Robot
inspector** (bundled with `robot-server`) against the running IDE to tighten the XPath.
