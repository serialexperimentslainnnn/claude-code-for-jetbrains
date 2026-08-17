# Telemetry & privacy

**Short version:** Claude Code Native collects nothing. There is no
analytics, no error reporting, no usage pings, no remote logging. The plugin
opens no network connection to anything off your machine — the one socket it
ever binds is a loopback one, described below.

## What stays on your machine

Everything the plugin keeps, it keeps locally:

- **Transcripts.** The plugin persists none of its own. Chat history is the
  `claude` binary's files, under `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`
  — the same ones `claude --resume` reads in your terminal. The plugin only reads
  them.
- **Which tabs were open.** `SessionHistory` stores the ordered list of
  `sessionId`s in the project's `workspace.xml`, which is not committed. Ids
  only, no content.
- **Settings.** Since 5.5.0 they live in the **IDE's PasswordSafe** — the OS
  credential store (Keychain, KWallet/Secret Service, Credential Manager) or the
  IDE's encrypted file — as one JSON document, application-wide rather than per
  project. A `.idea/claude-code.xml` left by an older version is adopted into the
  safe once and then deleted: that file is per project, plaintext and
  committable, and these settings carry an env block, which is where an API key
  or a credentialed proxy URL ends up.
- **Credentials.** The OAuth blob is harvested into that same safe and
  `~/.claude/.credentials.json` is deleted; API keys sit in their own safe slot.
  They reach the binary as environment variables — never as arguments, never in
  a log, never in the transcript.
- **Which agents this plugin spawned.**
  `~/.claude/ide/claude-code-native/agent-index.json` — ids, who spawned whom,
  the agent *type* (`general-purpose` and the like) and whether you had the tab
  open. No prompts, no descriptions, no transcript content. It exists so that
  after a restart your agents can be told apart from ones a terminal session
  left in the same directory.
- **Logs.** The IDE's own `idea.log`, on your machine. Nothing is uploaded.
- **The one socket.** The chat page is normally handed to the embedded browser
  without any network at all. Where that cannot work — Remote Development, where
  the document lives on the backend and the client reaches it through a port
  forward — the plugin serves that one document over HTTP bound to the
  **loopback address only**, on an OS-assigned port, behind a **one-shot token**;
  any other path gets an empty 404. Nothing off-host can connect to it, and the
  only thing it can ever serve is the plugin's own UI.

## What goes off-machine, and why

- **Your prompts and the model's responses** travel between the `claude`
  binary and Anthropic's API. That channel is owned by the binary and
  authenticated with your own credential (subscription / OAuth /
  `ANTHROPIC_API_KEY`), which the plugin hands to it in the environment. The
  plugin does not add, intercept, or duplicate this traffic. Anthropic's privacy
  policy applies to that channel.
- **JetBrains MCP server, if enabled,** talks to the local IDE process
  only.
- **Custom MCP servers** you configure may make network calls — that is
  on you.

## What the plugin does NOT do

- No third-party analytics SDK (no Mixpanel, Amplitude, Segment, GA, etc.).
- No Sentry / Bugsnag / Rollbar.
- No call-home on startup, shutdown, or update check.
- No telemetry to JetBrains beyond what the IDE itself does (which the
  plugin neither configures nor influences).

## Future opt-in

If error reporting is ever added, it will be:

- **Opt-in**, never opt-out.
- Configured under **Settings ▸ Claude Code**.
- **Disclosed in `CHANGELOG.md`** under a `Security` or `Privacy` entry
  before the feature ships.
- **Anonymous by default** — no prompt content, no file contents, no
  project paths.

Until that day, this document remains accurate.

## GDPR positioning

Because the plugin processes no personal data of its own, it has no
controller / processor role under GDPR. The data flowing through the
`claude` binary to Anthropic is governed by your contractual relationship
with Anthropic. We design with minimisation, purpose limitation, and
storage limitation in mind, but we make no compliance certification claim.

For security disclosures, see [`../SECURITY.md`](../SECURITY.md).
