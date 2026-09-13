# Driving the IDE from any MCP client

The four IDE servers — `code`, `run`, `vcs`, `ops` — are ordinary MCP servers that happen to live inside
the plugin. Claude Code is their first client, not their only one: anything that speaks MCP drives the IDE
the same way, with the same tools ([`SKILL_INVENTORY.md`](SKILL_INVENTORY.md)) and under the same
Security Guard. The reference client is `src/test/kotlin/dev/lain/claudejb/mcp/McpClient.kt`, exercised
end to end by `McpClientHeadlessTest`: connect, authenticate, `initialize`, `domains`, `read_file`.

## Where the servers are

One set per open project, started when a chat tab launches `claude` — or, when the chat page cannot be
drawn (see *Split mode*), by the plugin on its own. They listen on Unix sockets, never on a port:

```
<IDE temp>/claude-ide-mcp/<random id>/
  code.sock  run.sock  vcs.sock  ops.sock   # one per server that has at least one live domain
  token                                     # the current token, mode 0600; the directory is 0700
```

`<IDE temp>` is `PathManager.getTempPath()`, falling back to `java.io.tmpdir` when the socket path would
be too long. The id is random per project open and the directory dies with the project. To find it:

- the `claude` process the plugin launched received it in `--mcp-config` (`ps` shows the socket path in the
  bridge's arguments);
- `idea.log` prints `IDE MCP servers listening under <dir>` when they start;
- in split mode, the notification *The chat could not be shown, but the IDE MCP servers are up* offers
  **Copy MCP configuration**, a ready `mcpServers` block.

A server whose plugins are all missing (for example `vcs` without Git4Idea) has no socket at all.

## Two ways in

### 1. stdio, through the bundled bridge

Every MCP client understands a stdio server. The plugin ships one, dependency-free, that translates
standard JSON-RPC lines on stdin/stdout to the socket's wire and attaches the token itself:

```json
{
  "mcpServers": {
    "code": {
      "type": "stdio",
      "command": "<java of the IDE>/bin/java",
      "args": ["-cp", "<plugin dir>/lib/*", "dev.lain.claudejb.mcp.StdioBridge", "<dir>/code.sock"]
    }
  }
}
```

This is exactly what the plugin hands to `claude` (`McpConfigBuilder.ownMcpServer`). The IDE's own Java
runs the bridge because it is the one that is certainly there; the bridge has no dependencies.

### 2. the socket itself

For a client that would rather skip the process:

- **Framing**: each message is its byte length in decimal ASCII, a newline, then the payload
  (`Frames.java`). Both directions. The ceiling is `Frames.MAX_FRAME_BYTES`.
- **Payload**: JSON-RPC 2.0, encoded as [TOON](https://github.com/toon-format/toon) rather than JSON
  (`model/mcp/toon/`). The reply is TOON too; decode it back to JSON.
- **Authentication**: every request and notification carries the token in
  `params._meta["dev.lain.claudejb/token"]` (`StdioBridge.TOKEN_KEY`). Read it from the `token` file beside
  the socket. It rotates every `TokenRing.ROTATION_MILLIS` with a `TokenRing.DEFAULT_OVERLAP_MILLIS` grace
  period, so a long-lived client re-reads the file when a reply says `request rejected` — that message is
  all the server says, whatever was wrong.
- **Methods**: `initialize`, `ping`, `tools/list`, `tools/call`, `server/discover`; the notification
  `notifications/cancelled` with `requestId` drops a pending call.
- **Tools**: three meta-tools, `domains()`, `tools(domain)`, `run(tool, args)`; the answer is
  `result.content[0].text`, TOON. Unknown tool or bad arguments come back as `isError: true` with the reason.
- **Concurrency**: one connection carries up to `ServerEndpoint.QUEUE_DEPTH` calls in flight; replies
  come back as each tool finishes, not in request order — correlate by `id`.

## Who may connect

The token is the lock: without it, nothing answers. A connection the plugin did not launch itself is
announced with a notification, and Settings ▸ Claude Code ▸ *Ask me before an unexpected client may talk
to our servers* holds it until the user answers **Allow** (closing the notice rejects it). Whoever the
client is, the Security Guard judges every `run(tool, args)` before anything runs.

## Split mode

In Remote Development a plugin that is not split loads **on the backend only**, and the UI it draws
there is projected to the frontend at poor fidelity; JCEF is a frontend API. So the plugin, its sockets,
its token and the `claude` process it launches are all on the backend host, and the chat page cannot be
drawn at all.

The plugin does not ask the platform whether it is split — those APIs are internal
(`RemoteDevApiContractTest`). It notices the chat page failing to arrive over every delivery route
(`PageDelivery`), and then starts the servers anyway and raises the notification above with the
configuration to copy. A client on the backend host — a `claude` in an SSH shell, or anything that reads
this page — drives the IDE without the chat. A client on the frontend machine cannot reach a Unix socket
of another host; that is by design, not an omission.
