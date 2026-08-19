package dev.lain.claudejb.ui.jcef

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object RemoteDevNotice {

    fun sshCommand(port: Int): String = "ssh -L 127.0.0.1:$port:127.0.0.1:$port <user>@<remote-host>"

    fun html(port: Int): String {
        val document = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="${csp(STYLE)}">
            <title>Claude Code — connection to the IDE backend</title>
            <style>@STYLE@</style>
            </head>
            <body>
            <main>
            <h1>Claude Code cannot reach the IDE backend</h1>
            <p>
              The chat is a web page served by the IDE backend on loopback port <strong>$port</strong>. This
              client never got through to that port, so there is nothing to display.
            </p>
            <h2>Open the forward</h2>
            <p>Run this on your own machine, in a terminal, with the placeholder replaced by the backend you connect to:</p>
            <pre><code>${esc(sshCommand(port))}</code></pre>
            <p>
              The left-hand address is your own machine and the right-hand one is the IDE backend, which is why
              the port is the same number on both sides. A JetBrains Gateway or SSH connection can carry the same
              forward, so adding it there works as well as a separate terminal.
            </p>
            <h2>Then retry</h2>
            <p>With the forward up, reopen the Claude Code tab, or open a new chat, and the transcript loads.</p>
            </main>
            </body>
            </html>
        """.trimIndent()
        return document.replace("@STYLE@", STYLE)
    }

    private val STYLE = """
        :root { color-scheme: light dark; --bg: #ffffff; --fg: #1c1c1e; --dim: #4a4a4f; --line: #d0d0d5; --code: #f2f2f4; }
        @media (prefers-color-scheme: dark) {
          :root { --bg: #1e1f22; --fg: #dfe1e5; --dim: #b6b9bf; --line: #494b50; --code: #26282b; }
        }
        html { background: var(--bg); }
        body {
          margin: 0;
          padding: 1.5rem 1.25rem 2.5rem;
          background: var(--bg);
          color: var(--fg);
          font-family: -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;
          font-size: 14px;
          line-height: 1.6;
        }
        main { max-width: 62ch; margin: 0 auto; }
        h1 { font-size: 1.35rem; line-height: 1.3; margin: 0 0 0.75rem; }
        h2 { font-size: 1rem; margin: 1.75rem 0 0.5rem; }
        p { margin: 0 0 0.75rem; color: var(--dim); }
        strong { color: var(--fg); }
        pre {
          margin: 0 0 0.75rem;
          padding: 0.75rem 0.85rem;
          border: 1px solid var(--line);
          border-radius: 8px;
          background: var(--code);
          color: var(--fg);
          overflow-wrap: anywhere;
          white-space: pre-wrap;
          user-select: text;
        }
        code { font-family: ui-monospace, 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 13px; }
    """.trimIndent()

    private fun csp(style: String): String =
        "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; " +
            "script-src 'none'; img-src 'none'; connect-src 'none'; font-src 'none'; " +
            "style-src 'sha256-${sha256Base64(style)}'"

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sha256Base64(s: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)),
        )
}
