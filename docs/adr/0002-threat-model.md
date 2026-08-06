# ADR 0002 — Threat model: what the plugin defends against, and what it does not

- **Status:** accepted
- **Date:** 2026-08-05
- **Context skill:** `appsec-standards`

## Context

`permission/SensitiveGuard.kt` is the strongest control in the plugin and it is genuinely deterministic —
plain Kotlin, out of band, with no prompt that argues it into a Yes. But until now it defended without ever
stating **against what**. That is a real gap and not a documentation one: a control whose adversary is
unwritten cannot be reviewed, its coverage cannot be argued about, and every discussion of a proposed bypass
restarts from first principles.

This ADR writes the adversary down. It is the citable version of what the KDoc already implies, and
`SECURITY.md` links to it so a reporter can tell a finding from a non-finding before writing the email.

**Assets, in the order we would miss them.** The user's credentials and key material (SSH, GPG, cloud, cluster,
browser stores, agent tokens — the ones that grant access to *other* systems); the integrity of the working
tree; the user's wider filesystem outside the project; and the IDE process itself.

## The trust model, stated once

Three principals, and only one of them is trusted:

| Principal | Trust | Why |
|---|---|---|
| The user | Trusted | It is their machine, their uid, their repository. Every control here exists to inform their decision, never to overrule it. |
| The `claude` binary | Trusted **as software**, untrusted **as a channel** | Anthropic's signed binary, running as a child process. We do not defend against it being malicious; we do defend against what it *relays*. |
| Everything the binary relays | **Untrusted** | Model output, tool inputs, MCP server traffic, file contents, fetched pages. All of it is attacker-influenceable. |

The load-bearing line is the third. The model is not the adversary — the adversary is whoever wrote the
content the model read. That reframing is what makes the design tractable: we are not trying to make an LLM
behave, we are treating its output as untrusted input to a policy engine, which is an ordinary software
problem with ordinary software answers.

## Surface 1 — the `claude` binary as a child process

**Spoofing.** A `claude` on `PATH` that is not Anthropic's. `ClaudeBinaryLocator` resolves and validates a
preinstalled binary rather than downloading one; a user who has been convinced to install a trojanned CLI has
already lost, and the plugin does not claim otherwise. *Accepted, out of scope, stated.*

**Tampering / Elevation.** The binary writes files, not the IDE — so the plugin's leverage is the answer it
gives to `can_use_tool`. Writes are confined to the project root (`DiffPresenter.isWithinRoot`, enforced in
`PermissionBroker` and `FileRollback`), on canonical paths so a symlink cannot escape. The auto-approving
modes are never passed through to the binary: `SessionLauncher.binaryPermissionMode` rewrites both
`acceptEdits` and `bypassPermissions` to `default`, so the binary asks for **every** tool call and the
auto-approval happens host-side, after the guard. That is what lets `SensitiveGuard` hold in a mode whose
name promises it will not.

**Information disclosure.** The credential blacklist matched structurally (wherever the file sits, across
Windows/WSL/POSIX layouts), plus the dangerous-command patterns for exfiltration, evaluated after
de-obfuscation and path canonicalisation.

**Repudiation.** Every decision is a visible card; nothing auto-approves silently in the categories above,
including when a per-rule toggle is off — a disabled rule downgrades DENY to ASK, never to ALLOW.

**Denial of service.** A wedged binary stalls one chat tab. The 30 s control-request watchdog and the
drain-on-stop path bound it. *Low severity, accepted.*

## Surface 2 — third-party MCP servers

MCP servers are configured by the user and run with the user's privileges. They are code we did not write,
reached through a channel we do, and they can name any tool and any input they like.

The decision that follows is deliberately blunt: **an MCP server or a Skill that touches credential material
or foreign territory is denied outright, not asked about.** A permission card is a request for a human
judgement, and the judgement here is available in advance — no legitimate MCP server needs the user's SSH
key. Making it a card would be theatre that trains the user to click through.

This is the reason the caller-trust check is an **allowlist** (`AGENT_TOOLS`) and not a blocklist: a
blocklist is a list of names, and names are attacker-supplied. 4.4.0 is the cautionary tale — the allowlist
had gone stale as the CLI grew its own orchestration surface, so *first-party* tools fell into the untrusted
branch and were hard-denied. The failure was safe, which is the point of choosing the allowlist, but it was
still a failure, and it argues for regenerating that list from the vendored SDK schema rather than curating
it by hand.

## Surface 3 — model-returned content (indirect prompt injection)

The one that matters most and is defended least directly, because it cannot be defended directly.

A repository file, an issue body, a fetched page or an MCP response can carry instructions aimed at the
model. We do not attempt to detect them — content-level detection of prompt injection is an unsolved problem
and a control built on it would be a liability, not a defence. **We assume injection succeeds**, and place
the controls where success does not pay:

- **Consequences, not intentions.** `SensitiveGuard` judges the *tool call*, never the reasoning behind it.
  A perfectly-injected model still has to ask to read `~/.ssh/id_ed25519`, and the answer is the same
  whatever it says about why.
- **Rendering is not execution.** Model text goes through `marked` then `DOMPurify`, into a page with
  `default-src 'none'`, `connect-src 'none'`, no `'unsafe-inline'`, and scripts allowed only by exact sha256
  hash. Injected markup cannot execute, and the page cannot open a socket to exfiltrate what it can see.
  Links never navigate: they are routed to the host, and `jb://` opens are gated by `LinkResolver.isOpenable`.
- **Confused-deputy containment.** The write gate is project-root; the open gate is project ∪ `$HOME`. Both
  are canonical and symlink-safe. The asymmetry is intentional and argued in `SECURITY.md`: showing a user
  their own file crosses no boundary, writing to one does.

**What this does not cover, plainly:** an injected model can still do damaging things *inside* the project
root that the user approves because they look plausible. Nothing here substitutes for reading the diff. The
plugin's contribution is that the diff is in front of you, editable, before the write happens — not that it
knows which diffs are hostile.

## Explicitly accepted, non-goals

- **Defending against the user.** Every control is bypassable by a user determined to bypass it, by design.
- **Defending against a compromised host.** An attacker with the user's uid does not need this plugin.
- **Detecting every obfuscation.** Recognising a path inside an arbitrary shell string is best-effort; an
  encode-and-`eval` may not match. That is a gap in *recognition*, closed by widening patterns. Enforcement
  of a match, once made, is absolute — the two must not be conflated when triaging a report.
- **Sandboxing the binary.** It runs with the user's privileges. Containment is the permission surface, not
  the OS. Whoever wants an unconstrained agent has the CLI, where the controls are Anthropic's.

## Consequences

- `SECURITY.md` links here, so "is this a finding?" has a written answer.
- Any change to `SensitiveGuard`, `PermissionBroker` or the CSP is reviewed against this document, and a
  change that invalidates one of its claims updates it in the same commit.
- The trust boundary is now testable as a claim, not just as behaviour: the allowlist regeneration
  (4.4.0) and the caller-trust matrix in `PermissionBrokerTest` are the concrete artifacts of Surface 2.
