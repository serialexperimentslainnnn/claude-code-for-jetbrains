# Security Policy

Claude Code Native is an open-source IntelliJ Platform plugin distributed via the
JetBrains Marketplace (id `dev.lain.claude-code-for-jetbrains`). We take security
seriously and follow responsible disclosure.

## Supported versions

| Version | Supported          |
|---------|--------------------|
| 2.x     | Yes (active)       |
| < 2.0   | No                 |

Only the latest minor of the 2.x line receives security fixes. Users on older
2.x patch releases must upgrade to the latest before a fix is backported.

## Reporting a vulnerability

Please **do not** open a public GitHub issue, discussion, or Marketplace review
for security problems.

Email: **dev@digitalexperiments.dev**
Subject line: `[SECURITY] Claude Code Native <short summary>`

Include:

- Affected plugin version (Settings → Plugins → Claude Code Native).
- IDE product and build number (Help → About).
- OS and version.
- Reproduction steps, proof-of-concept, expected vs observed impact.
- Whether the issue is already public anywhere.

PGP-encrypted email is welcome; request our key in a first plaintext message
that contains no sensitive details.

## Our commitments

- **Acknowledgement:** within 48 hours of receipt.
- **P0 (active exploitation, RCE, credential exfiltration):** patch within
  24 hours and an emergency Marketplace release.
- **P1 (high severity, no known exploitation):** patch within 7 days.
- **P2/P3:** rolled into the next scheduled release with credit in
  [`CHANGELOG.md`](CHANGELOG.md) under `Security`.

We will coordinate on a disclosure timeline with the reporter and credit them
in the changelog unless they prefer to remain anonymous.

## In scope

- Kotlin/Swing code in `src/main/kotlin/dev/lain/claudejb/`.
- Build configuration and Gradle dependencies declared in `build.gradle.kts`.
- Protocol handling against the `claude` binary's stream-json/control surface.
- Permission gating, path-traversal guards, env handling, source-script trust.

## Out of scope

These are valid security concerns but **not** for this repository:

- The `claude` binary itself — report to **Anthropic**
  (<https://www.anthropic.com/security>).
- The IntelliJ Platform / JetBrains IDE — report to **JetBrains**
  (<https://www.jetbrains.com/legal/docs/privacy/security/>).
- The bundled JetBrains MCP Server plugin — report to **JetBrains**.
- Vulnerabilities in third-party MCP servers a user configures themselves.
- Issues only reproducible with a modified plugin build.

## Not accepted as security issues

- Missing security headers on third-party services we link to.
- Self-XSS via the user pasting hostile content into their own chat.
- "Plugin can run shell commands when the user approves a tool" — that is the
  documented behaviour, gated by `can_use_tool` and the permission UI.
- Social-engineering scenarios that require the attacker to already control
  the user's machine, IDE settings, or `~/.claude/` directory.
- Reports generated solely by automated scanners with no demonstrated impact.
- Outdated `node_modules/@anthropic-ai/claude-agent-sdk/` files — these are
  kept as **protocol reference only**, are not executed, and are not shipped
  in the plugin distribution.

## Disclosure

Once a fix is released, we publish a short advisory in
[`CHANGELOG.md`](CHANGELOG.md) under `Security` and, when warranted, a GitHub
Security Advisory with a CVE request.
