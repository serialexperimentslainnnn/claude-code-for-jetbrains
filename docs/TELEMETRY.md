# Telemetry & privacy

**Short version:** Claude Code Native collects nothing. There is no
analytics, no error reporting, no usage pings, no remote logging. The
plugin opens no network connections of its own.

## What stays on your machine
1
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
