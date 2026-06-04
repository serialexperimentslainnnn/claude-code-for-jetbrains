# Telemetry & privacy

**Short version:** Claude Code Native collects nothing. There is no
analytics, no error reporting, no usage pings, no remote logging. The
plugin opens no network connections of its own.

## What stays on your machine

- **Conversation transcripts.** The plugin does **not** persist any
  conversation content. The `claude` binary writes its own session files
  under `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`; the plugin
  reads them via `SessionTranscriptReader` when you reopen a session, but
  it never copies, ships, or syncs them. To purge transcripts, delete
  files under `~/.claude/`.
- **Workspace state.** The list of open chat-tab `sessionId`s for the
  "restore on startup" feature is stored in the IDE's per-project
  `workspace.xml`. This file is normally git-ignored.
- **Settings.** Model / mode / effort / thinking / allowed-tools / env
  vars / custom MCP servers live in the IDE's per-project
  `claude-code.xml`. No secrets are written there by the plugin; if you
  put a secret into the environment-variables setting it stays in that
  file in plaintext — Settings displays a warning when you do.
- **IDE logs.** The plugin uses `Logger.getInstance(...)` like any other
  IntelliJ Platform component. Entries land in `idea.log` (see
  [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md#logs)) and stay on disk
  according to your IDE's log rotation. They are never read or transmitted
  by the plugin.

## What goes off-machine, and why

- **Your prompts and the model's responses** travel between the `claude`
  binary and Anthropic's API. That channel is owned by the binary and
  authenticated with the credentials in your `~/.claude/` directory
  (subscription / OAuth / `ANTHROPIC_API_KEY`). The plugin does not add,
  intercept, or duplicate this traffic. Anthropic's privacy policy applies
  to that channel.
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
- **Per project**, configured under Settings → Tools → Claude Code Native.
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
