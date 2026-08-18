# Security Policy

Claude Code Native is an open-source IntelliJ Platform plugin distributed via the
JetBrains Marketplace (id `dev.lain.claude-code-for-jetbrains`). We take security
seriously and follow responsible disclosure.

## Supported versions

| Version | Supported    |
|---------|--------------|
| 5.x     | Yes (active) |
| < 5.0   | No           |

Only the latest release of the current major receives security fixes. There is
no backporting to earlier majors: the plugin ships through the JetBrains
Marketplace, which auto-updates, so "upgrade to the latest" is a one-click fix
for every user. Users on an older patch release must upgrade before reporting.

## Reporting a vulnerability

Please **do not** open a public GitHub issue, discussion, or Marketplace review
for security problems.

**Use GitHub's private vulnerability reporting:**
[Report a vulnerability](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/security/advisories/new)
(repository → **Security** → **Report a vulnerability**).

This replaces the email address that used to be published here, and it is the
better channel on its own merits, not just a privacy measure: the report lands
in a private thread attached to this repository, the discussion and the fix
stay linked to it, and a CVE can be requested from the same advisory. An
address in a public file is scraped far more often than it is used by a
reporter.

Include:

- Affected plugin version (Settings → Plugins → Claude Code Native).
- IDE product and build number (Help → About).
- OS and version.
- Reproduction steps, proof-of-concept, expected vs observed impact.
- Whether the issue is already public anywhere.

The advisory thread is already private, so there is nothing to encrypt against —
and there is deliberately **no contact address published anywhere in this
repository**. If you need an out-of-band channel, ask for one in the advisory.

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

- Kotlin code in `src/main/kotlin/dev/lain/claudejb/`.
- The inlined JCEF web app in `src/main/resources/jcef/` and its vendored
  libraries (`marked`, `DOMPurify`, `highlight.js`) — these **do** ship.
- Build configuration and Gradle dependencies declared in `build.gradle.kts`.
- Protocol handling against the `claude` binary's stream-json/control surface.
- Permission gating, path-traversal guards, env handling, source-script trust.

### Scope of dependency triage: the artifact, not the repository

We triage advisories against **what we distribute**, which is the signed plugin
zip. Concretely, that means the JVM dependencies resolved into the jar plus the
JavaScript vendored under `src/main/resources/jcef/`. A finding in either is in
scope and is treated as a defect.

The repository also carries a `package.json`, and it is **build tooling only**:
`vitest`/`jsdom` for the frontend tests, `commitlint` for the commit gate, and
`@anthropic-ai/claude-agent-sdk` as the protocol reference that
`./gradlew checkDrift` diffs the binary's surface against. None of it is
executed by the plugin and none of it is packaged — all of it is declared under
`devDependencies`, and `npm audit --omit=dev` (the distributed scope) reports
zero. `npm audit` over the whole tree will report transitive advisories in that
tooling; they reach a developer's machine at build time, never a user, and are
handled as maintenance rather than as security releases.

**This is enforced, not asserted.** `ci.yml` blocks on `npm audit --omit=dev
--audit-level=low` (the distributed scope) and reports the full tree without
failing; and the `Build plugin` job asserts, on the exact artifact the verifier
just checked, that the zip contains **zero** `node_modules` entries and that
`META-INF/LICENSE`, `META-INF/THIRD-PARTY-NOTICES.md` and a licence text for
**every** file in this repository's `LICENSES/` directory are present inside the
plugin jar. That last set is derived from the checkout rather than hardcoded, so
adding a licence text extends the gate by itself — and the notices file cannot
end up pointing at texts the artifact does not carry, which is exactly what it
did before the check was widened. Both are required status checks on `main`. If
the packaging ever changes, the claim fails the build rather than quietly
becoming false.

Verify it yourself — the artifact is inspectable:

```sh
unzip -l build/distributions/*.zip | grep -c 'node_modules'   # → 0
```

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
- Advisories in the repository's `devDependencies` — build tooling that is
  neither executed by the plugin nor packaged. See *Scope of dependency
  triage*, above.
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

## Where credentials and settings are kept

Both moved into the IDE's **PasswordSafe** — the OS credential store (Keychain,
KWallet/Secret Service, Credential Manager) or the IDE's encrypted file — and
both moves closed a plaintext-at-rest problem rather than a theoretical one.

- **The OAuth credential (5.0.x).** `claude auth login` writes
  `~/.claude/.credentials.json`, plaintext on Linux and shared with the terminal
  CLI. The plugin harvests it into the safe and **deletes that file** — including
  a login you made in your own terminal, deliberately. It is never written back:
  the credential reaches the binary as an environment variable, and the plugin
  holds no OAuth client and calls no token endpoint. Renewal (5.0.1) goes through
  the binary's own non-interactive refresh branch, so that invariant is intact.
- **API keys** live in their own per-provider slot, so no provider's key can
  overwrite another's, and are applied only when that provider is selected.
- **The settings document (5.5.0).** Previously `.idea/claude-code.xml`: per
  project, plaintext, and in a directory people commit. It carries the plugin's
  **env block**, which is exactly where an API key or a credentialed proxy URL
  ends up. The legacy file is deleted only after the safe has accepted the copy.

Credentials are passed in the environment, **never in argv** (where `ps` would
show them), and never reach a log, the transcript or any exported XML. Sign-out
clears the plugin's safe and nothing else — running `auth logout` from an IDE
button would destroy the user's terminal login too.

## The sensitive-data lock (4.3.1) — deterministic, not an "AI guardrail"

The strongest control in this plugin is not the model behaving. It is
`permission/SensitiveGuard.kt`: **plain Kotlin, out of band, that intercepts
every `can_use_tool` request before any auto-approval.** The model has no access
to this code and no say in its verdict — there is no prompt that argues it into a
Yes. This matters because the security of an AI agent is not, in the end, an AI
problem; it is an *old-fashioned software* problem, and it is solved with
old-fashioned software.

**What it defends against is written down**, so a report can be judged against a
stated adversary instead of against intuition:
[ADR 0002 — Threat model](docs/adr/0002-threat-model.md). Read it before
reporting; it says in advance which findings are real (a match that gets
auto-approved anyway) and which are known, accepted positions.

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
- **MCP servers and Skills → denied outright** by default;
- **foreign territory → denied for everyone** by default.

And the plugin **refuses to start at all** with the project rooted on a network
or remote drive: an autonomous agent — shell, IDE reach, coding ability — on
shared storage is a lateral-movement launchpad, and the friction (you cannot
casually relocate a network directory) is the point. Whoever wants the
unrestricted tool has the `claude` CLI, where the controls are Anthropic's.

The project root is the one **sanctioned zone**: a file you brought into your own
repo is yours, under your responsibility.

**Per-rule enforcement toggles (Settings ▸ Claude Code ▸ Security).** Each rule —
credentials, dangerous commands, and each of the three foreign-territory checks
(other users' homes, network/UNC mounts, foreign WSL drives) — has its own
on/off switch, defaulting **ON** (the behaviour above, exactly). Turning one off
is **never a silent allow**: detection still runs unconditionally, and a hit is
only *downgraded* from an automatic DENY to a permission card — shown every
time, to every caller, MCP and Skills included. There is no toggle that makes a
match invisible. This exists for a real, legitimate case (a project that
genuinely lives on a corporate network share, say) without gutting the model:
you still see and decide every hit, you just decide it yourself instead of the
lock deciding it for you.

**What is heuristic, stated plainly:** *detection* of a path inside an arbitrary
shell string is best-effort — an obfuscation cleverer than the de-obfuscator, or
a decode-and-`eval`, may not *match*. That is a gap in what we recognise, closed
by widening the patterns, **not** a way to argue with a match once made: at its
layer, enforcement is absolute. Report a bypass of the *decision* (a match that
is auto-approved anyway, a foreign/remote path that is reached) — that is a real
finding. A path we failed to *recognise* is a pattern PR.

## Release signing: three keys, three different claims

A release carries **two** signatures, and behind both stands a third key that
signs neither. Conflating any two of them is the mistake this section exists to
prevent: they answer different questions and have very different security
properties.

| | Hardware CAs | Maintainer key | CI signing key |
|---|---|---|---|
| Signs | the other two keys, and nothing else | every commit | the `vX.Y.Z` tag, the release `.zip` and its `.sha256` |
| Claim | *this key belongs to the project* | *a person wrote and merged this commit* | *this workflow cut this tag and produced these bytes* |
| Custody | **hardware (YubiKey)** — non-exportable, never on a computer | software key on the maintainer's machine | software key in a GitHub **environment** secret |
| Public key | `docs/trust-chain.asc` — Root `E70A 8865 89AB 9AB9 DC2D  2CA3 B746 AD2C 841D 5CE3`, Intermediate `318B BEFF 6E5D D5A0 3A82  8051 8DAB 773C 3796 B834` | `B12D B7CF BAC5 2556 672E  9B24 E2E4 041C CF03 9102`, registered on the GitHub account | `docs/trust-chain.asc`, the **last** block in the file |
| Expiry | 2029 | 2029 | **1 year**, then rotated |

**The two CAs are the anchor and they are deliberately idle.** Their private
halves are on two separate YubiKeys and have never existed as a file; all they
ever do is certify, which is why nothing routine needs them plugged in. The
maintainer key is a software key *because* of them — it can be replaced without
anyone re-learning a fingerprint, since what a reader anchors on is the pair
above it, not the key that happens to be signing commits this year.

**Why there is a second key at all, stated plainly.** The maintainer key cannot
sign inside a CI runner: it is hardware-backed and non-exportable, which is
exactly what makes it worth trusting. Automating artifact signatures therefore
requires a software key whose private half sits in a secret. That is a real
weakening and it is an accepted, bounded one:

- The secret is scoped to the **`marketplace` environment**, whose deployment
  policy admits only `main` and `v*.*.*` tags. No job on any other ref can see
  it, and no repository-level secret exists at all.
- The key **expires after a year**, so a leak nobody noticed stops mattering on
  its own schedule rather than never.
- Its user ID says out loud that it is a CI key and not the maintainer. If the
  two were indistinguishable, a leaked CI key would impersonate a person; being
  able to tell them apart is the whole mitigation.

**The CI key is certified by both hardware CAs.** `docs/trust-chain.asc` carries
those two certification signatures, each made on its own YubiKey, so the keys in
it are not independent claims: the hardware vouches for the CI key.

It is **one** file rather than three on purpose. A chain is imported whole or it
is not imported at all — the leaf on its own is a fingerprint in a repository,
and a CA on its own certifies nothing you have. Handing out three files invites
importing one.

This matters for a reason that is easy to miss. Without it, a reader is asked to
trust a fingerprint printed in a file **inside the same repository** an attacker
who could swap the key would also control — which is not a trust anchor, it is a
tautology. With it, the chain terminates at a key whose private half is in
hardware and has never been on a computer.

It also buys the one thing a bare key cannot: **a revocation lever.** If the CI
key is ever exposed, the maintainer revokes the certification from hardware,
withdrawing the endorsement immediately — without depending on anyone noticing
that a file changed.

```sh
# The CI key is the LAST block in the chain — the CAs come first, so take the last, not the first.
gpg --check-sigs "$(gpg --show-keys --with-colons docs/trust-chain.asc \
                    | awk -F: '$1=="pub"{getline; if ($1=="fpr") f=$10} END{print f}')"
# expect a certification from EACH of the two CA fingerprints in the table above
```

**A certification that does not survive export is the failure mode here**, and it
looks exactly like success locally: `gpg --lsign-key` (and Kleopatra's default
"certify for yourself only") makes a *local* signature, which is stripped the
moment the key is exported. The published chain then carries the CAs and no
endorsement at all. `scripts/bootstrap-ci.sh` re-imports its own output into a
throwaway keyring and checks the signatures are still there, because reading the
signing machine's keyring can only ever confirm what that machine already thinks.

**Verify both signatures.** They are complementary, not redundant — the artifact
signature covers the bytes you downloaded, and the tag ties those bytes to a
commit on `main`:

```sh
gpg --import docs/trust-chain.asc               # or trust-chain.asc from the release itself
gpg --verify claude-code-native-X.Y.Z.zip.asc   # bytes came from the workflow
git verify-tag vX.Y.Z                           # cut from main by that workflow
gh attestation verify claude-code-native-X.Y.Z.zip \
   --repo serialexperimentslainnnn/claude-code-for-jetbrains   # build provenance
```

**What no signature here claims.** Releases are cut automatically when `develop`
is merged into `main`, and both the tag and the artifact are signed by the CI
key — which is certified by the two hardware CAs, so the chain still ends in
hardware, but which signs without a human present. **Nothing in a
release attests that a person authorised it.** Read `git verify-tag` as *"this
workflow cut this from main"*, and treat the human judgement as living in the
pull request, not in the signature.

That judgement is **one** gate, not two. This section used to name a second — a
required reviewer on the `marketplace` environment — and there is no such rule:
the environment's only protection is the deployment policy restricting it to
`main` and `v*.*.*` (checked against the API on 2026-08-11; the provisioning
script sets `reviewers: []` deliberately). **The merge into `main` is the last
human act before a version reaches users.** Said plainly rather than dropped,
because a control everyone believes in and nobody configured is worse than one
that was never claimed. What still holds without it: `main` takes nothing but
pull requests that are up to date with every required check green (a
*mechanical* gate — `required_approving_review_count` is 0, deliberately, since
GitHub will not let an author approve their own PR and a single maintainer
cannot satisfy any higher value), the credentials exist on no other ref, and the
lineage guard runs before any of them is in scope.

The attestation is worth having and worth not overtrusting: it proves *where* a
build ran, not that the result is benign. A compromised runner can produce a
valid attestation for a malicious artifact. What actually reduces that risk is
everything around it — every action pinned by commit SHA, a read-only default
token, and no secrets outside the one environment-scoped job.

**Rotation** (scheduled, before expiry): regenerate with
`./scripts/gen-ci-signing-key.sh` — or `./scripts/bootstrap-ci.sh`, which does
the whole sequence — certify the new key with **both** YubiKeys, replace both
environment secrets, and commit the rewritten `docs/trust-chain.asc`.

Rotation **overwrites** that file, and the retired key is not kept beside its
successor: a bundle that accumulates every key the project ever used makes the
reader decide which one to believe, which is the one judgement the file exists to
spare them. Previously published releases stay verifiable because each release
carries the chain that was current when it was cut, attached as an asset — so
the retired key is still there, on the release it actually signed, which is where
anyone verifying that release is already standing.

**Compromise** (the CI key is exposed, or a runner is suspected compromised) —
in this order, because the first step is the only one that is immediate:

```sh
# 1. Withdraw BOTH endorsements — one is enough to keep the key looking endorsed.
#    Takes effect for anyone who refreshes the key.
gpg --local-user E70A886589AB9AB9DC2D2CA3B746AD2C841D5CE3 --quick-revoke-sig <CI_FPR> <CI_FPR>
gpg --local-user 318BBEFF6E5DD5A03A8280518DAB773C3796B834 --quick-revoke-sig <CI_FPR> <CI_FPR>
{ gpg --armor --export E70A886589AB9AB9DC2D2CA3B746AD2C841D5CE3 318BBEFF6E5DD5A03A8280518DAB773C3796B834
  gpg --armor --export <CI_FPR>; } > docs/trust-chain.asc   # now carries the revocations

# 2. Delete the secrets so nothing can sign with it again.
gh secret delete GPG_SIGNING_KEY        --env marketplace
gh secret delete GPG_SIGNING_PASSPHRASE --env marketplace

# 3. Issue a new key, and publish an advisory naming the exposed fingerprint and
#    the releases signed with it.
```

Note the ordering: revoking the certification is a **hardware** action that no
attacker holding the CI key can undo, and it does not require the compromise to
have been noticed by users. Deleting the secret stops future signatures but says
nothing about the ones already made.

## Disclosure

Once a fix is released, we publish a short advisory in
[`CHANGELOG.md`](CHANGELOG.md) under `Security` and, when warranted, a GitHub
Security Advisory with a CVE request.
