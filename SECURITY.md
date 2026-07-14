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

Email: **lain.agent604@passmail.com**
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
- **A link or a model suggestion that opens one of the user's own files in
  their own editor.** See below — this is a deliberate, documented position.

### Opening a user's own file is not a privilege boundary

Since 4.3.1 the transcript renders **jump-to-code links**, and the gate that
authorises opening one (`LinkResolver.isOpenable`) allows the **project tree
and the user's `$HOME`**, refusing everything else (`/etc`, `/usr`, another
user's files) on *canonical* paths, so symlinks cannot escape it.

We will not accept reports of the form *"the model can emit a link — or simply
suggest a path — that opens `~/.ssh/id_rsa` in the editor"*. The reasoning,
stated once so it need not be re-litigated:

- **No trust boundary is crossed.** The file is opened in the user's own IDE,
  under the user's own uid, and shown to the user. They could already open it
  with *Go to File*. Nothing is read, sent, or written; no privilege is
  gained. What is described is UI phishing, not escalation.
- **It grants the model no new capability.** The agent can already *read* any
  file the user can, through its own `Read` tool — under `can_use_tool` and the
  permission UI, which is where that decision belongs and where it stays.
  A link adds nothing to the agent's reach.
- **The control lives at the right layer.** What the agent may read, and what
  it may do with it, is governed by the permission modes, the allow/deny tool
  lists and the user's `CLAUDE.md` — not by refusing to render a hyperlink.
  Guarding *what the agent may touch* is a real problem, and we treat it as
  one (see *Sensitive files*, below); guarding *what the user may look at on
  their own screen* is not.
- **An attacker who can already see the user's screen, or who controls their
  machine, does not need the plugin** to reach these files.

The boundary we *do* enforce, and where reports are very welcome: the
**write** gate. What the binary is allowed to modify stays confined to the
project root (`DiffPresenter.isWithinRoot`, enforced in `PermissionBroker` and
`FileRollback`) and is unaffected by the above. A path that lets the plugin
**write**, delete or execute outside the project root — or an *open* that
reaches outside project ∪ `$HOME` — is a real finding. Report it.

## The sensitive-data lock (4.3.1) — deterministic, not an "AI guardrail"

The strongest control in this plugin is not the model behaving. It is
`permission/SensitiveGuard.kt`: **plain Kotlin, out of band, that intercepts
every `can_use_tool` request before any auto-approval.** The model has no access
to this code and no say in its verdict — there is no prompt that argues it into a
Yes. This matters because the security of an AI agent is not, in the end, an AI
problem; it is an *old-fashioned software* problem, and it is solved with
old-fashioned software.

It enforces three blacklists, and one whitelist:

- **Credentials & key material** — SSH/GPG keys, cloud & cluster credentials,
  database and shell-history secrets, browser/password-manager stores, crypto
  wallets, and the access tokens of every well-known AI agent and code-host
  (matched *structurally, wherever the file sits*, so `C:\Users\…\.ssh` and WSL's
  `/mnt/c/Users/…/.ssh` are covered by the same rule).
- **Dangerous commands** — secret dumps (`gpg --export-secret-keys`,
  `security dump-keychain`), exfiltration (`curl -T`, `nc`, a key piped out),
  reverse shells, LOLBINs, and recognised offensive tooling. Judged after a
  **de-obfuscation** pass (broken quotes, `$IFS`, variable laundering,
  base64→`sh`), and after **canonicalising** paths on disk so a symlink or `..`
  cannot hide a target.
- **Foreign territory** — another user's home, a network/NFS/CIFS/SSHFS mount, a
  UNC path, or (under WSL) any `/mnt/` drive other than `/mnt/c`.

Enforcement, by trust of the *caller* — an **allowlist**, so an attacker cannot
name their way in:

- the agent's own tools → a permission card **every time, even in
  `bypassPermissions` / `acceptEdits`** (the plugin launches the binary in
  `default` mode always, so it answers every `can_use_tool`);
- **MCP servers and Skills → denied outright**, no opt-in;
- **foreign territory → denied for everyone.**

And the plugin **refuses to start at all** with the project rooted on a network
or remote drive: an autonomous agent — shell, IDE reach, coding ability — on
shared storage is a lateral-movement launchpad, and the friction (you cannot
casually relocate a network directory) is the point. Whoever wants the
unrestricted tool has the `claude` CLI, where the controls are Anthropic's.

The project root is the one **sanctioned zone**: a file you brought into your own
repo is yours, under your responsibility. Everything else is off-limits, with no
setting to soften it — the escape hatch is "copy it into your project," on
purpose.

**What is heuristic, stated plainly:** *detection* of a path inside an arbitrary
shell string is best-effort — an obfuscation cleverer than the de-obfuscator, or
a decode-and-`eval`, may not *match*. That is a gap in what we recognise, closed
by widening the patterns, **not** a way to argue with a match once made: at its
layer, enforcement is absolute. Report a bypass of the *decision* (a match that
is auto-approved anyway, a foreign/remote path that is reached) — that is a real
finding. A path we failed to *recognise* is a pattern PR.

## Disclosure

Once a fix is released, we publish a short advisory in
[`CHANGELOG.md`](CHANGELOG.md) under `Security` and, when warranted, a GitHub
Security Advisory with a CVE request.
