# Release checklist

Copy this checklist into the release PR description and tick each box. The
full procedure is in [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md); this
file is the verifiable per-release gate.

## Pre-flight

- [ ] On a clean working tree on `develop` (or `hotfix/*` for a hotfix).
- [ ] `git pull --ff-only` shows no surprises.
- [ ] Target version selected per SemVer rules (see procedure §Versioning).
- [ ] **No open bot pull requests against `develop`** (`gh pr list --base develop
      --state open`). `No bot PRs pending on develop` is a required check on
      `main` and will block the release PR until the queue is drained.

## Build & verification

- [ ] `./gradlew test` — green, with **no failures**; the only expected skips are
      the two Windows-only tests.
- [ ] `./gradlew detekt spotlessCheck` — static analysis and formatting clean,
      with `config/detekt/baseline.xml` **untouched** (it holds exactly two
      accepted `ClaudeSession` findings; regenerating it to make a build pass is
      how the gate stops meaning anything).
- [ ] `npm test && npm run lint && npm run format:check` — the shipped JCEF
      frontend passes vitest, ESLint and Prettier.
- [ ] `./gradlew koverVerify` — coverage gates hold (see **Coverage policy** below).
- [ ] `./gradlew verifyPlugin` — **Compatible** across the declared range: the
      floor (253) through the newest IDEA **and PyCharm** EAP/RC.
- [ ] Verifier report clean at all four failure levels the build declares —
      compatibility problems, internal API, override-only API, and **deprecated
      API**. A deprecation is a blocker here, not a warning.
- [ ] `./gradlew buildPlugin` produces
      `build/distributions/claude-code-native-X.Y.Z.zip`.

## Coverage policy

Coverage is gated **per package**, not globally, because the risk in this codebase is not evenly spread:
`permission/` decides whether the agent may read your SSH key, and `ui/` paints a browser. One global number
would either set the bar low enough that the guard could rot unnoticed, or high enough that the only way to
meet it is writing tests against Swing and JCEF that assert nothing anyone cares about.

Every bound sits slightly **below** what is measured — a gate that catches regression, not a target that
invites test-padding. **This table is the only place the measured figures live**; `build.gradle.kts` carries
the bounds and the exclusion list, and points here rather than repeating a number that would then have two
homes and no way to notice they had diverged. The two must agree, and keeping them agreeing is part of
releasing.

Regenerate the figures rather than trusting the ones below — a measurement ages in silence and nothing here can
notice when it has. `./gradlew cleanTest test koverXmlReport` writes `build/reports/kover/report.xml`; the
`<counter type="LINE">` and `<counter type="BRANCH">` elements under each `<package>` are the per-package rows,
and the ones at the root of the document are the **all gated code** row. Measured 2026-08-21:

| package | line % | branch % | gated |
|---|---|---|---|
| `permission/` | 97.5 | 79.6 | ✅ |
| `git/` | 90.3 | 82.7 | ✅ — the pure half only; four classes are excluded by name |
| `protocol/` | 88.9 | 27.8 | ✅ |
| `vuln/` | 82.0 | 47.8 | ✅ — `OsvHttp` and `VulnService` excluded by name; **`OsvScanner` is gated at 0 %** |
| `settings/` | 79.3 | 58.8 | ✅ |
| `session/` | 73.6 | 51.0 | ✅ |
| `diff/` | 71.8 | 64.7 | ✅ |
| **all gated code** | **81.87** | **49.82** | — the aggregate the second rule bounds |
| `context/`, `process/` | — | — | ❌ excluded — known gap |
| `ui/`, `ui/jcef/` | — | — | ❌ excluded — covered elsewhere |
| `actions/` | — | — | ❌ excluded — one delegate call each |
| `util/` | — | — | ❌ excluded — one line, and it needs a live platform to run |

`vuln/` carries the one **known debt** in this table: `OsvScanner` has no test at all and is deliberately left
inside the gate rather than excluded with its two neighbours, so the package figure keeps paying for it. It
needs a seam to be testable — it reaches `OsvHttp` through a direct call — and until it has one the package
floor is met by the rest of the package, not by the scanner.

The excluded rows carry no percentage on purpose. `reports.filters.excludes` removes those classes from the
**report**, not merely from the calculation, so they are absent from `report.xml` altogether and there is no
measured figure to quote. An estimate in this table would defeat the only reason it exists.

`git/` is gated and easy to miss: the exclusion names four classes, not the package, so everything else —
`GitCommitInfo`, `GitBranchTopology`, `GitRefInfo`, `GitRemoteInfo`, the pure half where a bug would be
silent — stays inside the gate and is subject to the floor like any other package.

**Excluded, and why it is stated rather than gated at a token value.** `ui/` needs a live IDE and a live
Chromium; it is covered by a different layer — the vitest suite, which drives the *real shipped JS* out of
`src/main/resources/jcef/`, plus the mandatory manual pass in §Smoke test below. `context/` and `process/` wrap
the OS (system clipboard, process spawn, shell environment) and most of what is uncovered there cannot execute
on a CI box. That is a **known gap**, listed so nobody mistakes it for coverage; the pure parts of both —
`ClipboardCli` and `ImageAttachments` in `context/` (`ClipboardCliTest`, `ImageAttachmentsTest`), and
`EnvScriptLoader.parse` in `process/` — *are* tested. Gating any of these at 20% would dress the same fact up
as a passing check. `build.gradle.kts`'s kover exclusion carries the same list as the ❌ rows above; the two
must agree.

**What `koverVerify` actually enforces.** Four bounds, and these are the numbers in the build:

| rule | scope | line | branch |
|---|---|---|---|
| `every gated package holds its floor` | each package on its own | ≥ 65 | ≥ 20 |
| `gated code as a whole` | the aggregate | ≥ 75 | ≥ 40 |

**Known limitation, and it is larger than it looks.** `KoverVerifyRule` has no per-rule filter — re-checked at
**0.9.9**, the version the build resolves, against the plugin's own DSL sources; nor can a report variant stand
in for one, since a variant is scoped by source set rather than by package. So a threshold per package cannot
be expressed at all, and the per-package figures in the table above are **recorded, not enforced**. Read the
consequences rather than the shape:

- **A floor is fixed by the weakest package.** On lines that is `session/`; on branches it is `protocol/`, far
  below everything else, which is why the branch floor is 20 while `permission/` measures 74.5. The branch
  floor detects a collapse, not a regression, and it says nothing whatsoever about `permission/` holding its
  own number.
- **The aggregate cannot cover for that.** `permission/` holds about a twentieth of the gated branch mass, so
  it could lose half its branch coverage and the aggregate would still clear 40.
- **The line aggregate has little headroom**: 76.99 against a bound of 75. A change that lands a large, lightly
  tested package can turn this red on its own, and that is the bound to look at first when it does.

If a future Kover adds per-rule filters, tighten `build.gradle.kts` so the per-package figures above become
gates instead of records.

## Documentation

- [ ] [`../CHANGELOG.md`](../CHANGELOG.md) updated with the new version,
      today's date, and entries under the right Keep-a-Changelog sections
      (`Added`, `Changed`, `Fixed`, `Security`).
- [ ] [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) updated with a
      user-facing narrative for the new version.
- [ ] **Both dates re-checked immediately before the merge.** They are stamped
      by hand while the notes are written and the release happens at the merge,
      so a PR that sits ships a date that is already wrong — and the
      `## [x.y.z]` block goes out **verbatim** as the GitHub Release body.
      Known defect, with its exit, in
      [ADR 0001 §4](adr/0001-release-process.md).
- [ ] `change-notes` renders cleanly in the Marketplace HTML — verify by
      running `./gradlew patchPluginXml` and inspecting
      `build/patchedPluginXmlFiles/plugin.xml` (the `<change-notes>` tag
      should contain the latest section, extracted by
      `latestReleaseNotesHtml()` in `build.gradle.kts`).
- [ ] [`../README.md`](../README.md) install instructions still match
      reality (Marketplace name, link, settings paths).
- [ ] [`BINARY_COMPAT.md`](BINARY_COMPAT.md) updated **only if** the protocol
      baseline or the IDE range moved, with a new row in the matching table.
- [ ] [`../CLAUDE.md`](../CLAUDE.md) and [`../PROJECTMAP.md`](../PROJECTMAP.md)
      reflect the release — the version, and anything that moved or was added.

## Version metadata

- [ ] `build.gradle.kts` `version` bumped to `X.Y.Z`.
- [ ] `since-build` / `until-build` in `build.gradle.kts` still cover the
      currently shipped EAP/RC.
- [ ] Plugin id (`dev.lain.claude-code-for-jetbrains`) and name
      ("Claude Code Native") unchanged unless this is a deliberate
      breaking release.

## Smoke test on a real IDE

Install the freshly built zip into a real IDE — not the Gradle sandbox —
and walk through a short end-to-end scenario.

Find the IDE config directory under the Toolbox install, e.g. on Linux:

```
~/.local/share/JetBrains/Toolbox/apps/intellij-idea/
```

Steps:

This step is **not automatable and not optional**: twice now a release passed
every automated check and was broken in the IDE (ADR 0001 §5).

- [ ] Settings → Plugins → ⚙ → Install Plugin from Disk → pick the new zip.
- [ ] Restart IDE.
- [ ] Tool window "Claude Code" appears on the right, and the chat **renders** —
      i.e. JCEF loaded. Do this on the newest IDE available, not only on the one
      you develop in: the 5.1.1 breakage was a classloader difference that only
      appeared from build 262.
- [ ] New chat: model chip, mode chip, effort chip, thinking chip all show
      the expected defaults.
- [ ] Send a prompt that triggers an Edit tool call — permission card
      appears inline, "View diff" opens an **editable** diff in the editor area
      (not a modal window).
- [ ] Accept it — the edit is written whole (per-hunk acceptance was removed in
      4.0.5), VFS refreshes, and the file shows the change.
- [ ] Run something that spawns a subagent — it gets its **own tab** with its own
      transcript, and the main transcript links to it instead of interleaving.
- [ ] Restart the IDE — the previously open chats are reopened via `--resume`,
      and you are **still signed in** (the vaulted credential renews itself from
      the refresh token rather than falling back to the sign-in card).

## Git hygiene

- [ ] Version-bump commit is a Conventional Commit (`build: bump the version to
      X.Y.Z`) — `commitlint` rejects the old `Release vX.Y.Z` subject.
- [ ] Every commit signed with the YubiKey (the ruleset requires signatures, and
      merge commit is the only merge method enabled, because squash and rebase
      would rewrite the commits and strip those signatures).
- [ ] PR `release/X.Y.Z` → `main` opened, full CI green.
- [ ] **No tag pushed by hand.** `release.yml` cuts and signs `vX.Y.Z` itself,
      with the CI key, inside the `marketplace`-scoped job. A hand-cut tag
      bypasses the merge and cannot be undone — published tags are immutable
      (ADR 0001 §3).
- [ ] `release.yml` reached `publish` and it completed. **There is no approval
      prompt** — the `marketplace` environment carries no required reviewer, by
      design, so the merge you just made was the last human act. See
      [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md) §Secrets.

## Post-release

- [ ] Marketplace listing shows the new version within ~20 minutes.
- [ ] GitHub Release out of draft, with five assets: the signed zip, its
      `.sha256`, a `.asc` for each, and `trust-chain.asc`.
- [ ] `git verify-tag vX.Y.Z` and `gpg --verify` on the artifact both pass
      against `docs/trust-chain.asc`, **and** `gpg --check-sigs` on the CI key
      shows a certification from each hardware CA. A chain that does not chain
      is the failure this asset exists to prevent, and it looks like success.
- [ ] Milestone for `vX.Y.Z` closed and linked issues closed.
- [ ] `develop` back in sync with `main` — via a **pull request**; `develop` is
      protected and a fast-forward is impossible anyway, since GitHub creates the
      merge commit on `main`.
- [ ] Auto-memory / project notes updated if release status changed.
