# Contributing to Claude Code Native

Thanks for considering a contribution. This plugin is GPLv3 and community
driven; PRs that improve correctness, UX parity with the original Claude
Code, or coverage of the stream-json/control protocol are very welcome.

Please skim [`CLAUDE.md`](CLAUDE.md) before writing code — it documents the
architectural decisions and the behavioural contract with the `claude`
binary. Doing so will save a review round-trip.
[`PROJECTMAP.md`](PROJECTMAP.md) is the shorter answer to "where does this live",
and [`AGENTS.md`](AGENTS.md) lists the commands CI runs.

## Branching model

- **`main`** — released versions only. Tags `vX.Y.Z` are cut from here.
- **`develop`** — default integration branch. Open PRs against `develop`.
- **`feature/*`**, **`bugfix/*`**, **`chore/*`** — short-lived branches from
  `develop`.
- **`release/X.Y.Z`** — temporary, opened against `main` at release time.
- **`hotfix/X.Y.Z`** — from `main` for emergency fixes; merged back to both
  `main` and `develop`.

## Workflow

1. **Fork** the repository on GitHub.
2. **Branch** from `develop`:
   ```bash
   git checkout develop
   git pull
   git checkout -b feature/short-description
   ```
3. **Code** following the conventions below.
4. **Test** locally (see "Running tests").
5. **Push** to your fork and open a **Pull Request against `develop`**.
6. Resolve review feedback.

**Merge commits only — squash and rebase are disabled at the repository level.**
That is a signing decision, not a taste in history: both rewrite commits, which
invalidates the author's signature and replaces it with GitHub's `web-flow` key.
Signed commits are a required rule on both protected branches, so keep your
commits signed and do not expect a squash button.

## Pull request requirements

Your PR will be merged once:

- [ ] CI is green. A PR into `develop` runs the JVM and frontend suites; the full
      gate (static analysis, dependency audit, plugin verifier, artifact
      assertions) runs at the `develop → main` door.
- [ ] New behaviour has tests — Kotlin under `src/test/kotlin/…`, shipped
      frontend under `src/test/frontend/`.
- [ ] If the change is user-visible, [`CHANGELOG.md`](CHANGELOG.md) and
      [`RELEASE_NOTES.md`](RELEASE_NOTES.md) have an entry **under the version
      being prepared**. Neither file carries an `Unreleased` section, on
      purpose: `release.yml` publishes the newest `## [x.y.z]` block of the
      changelog verbatim as the release body, so a non-version heading at the
      top would ship as the release notes.
- [ ] `verifyPlugin` reports **Compatible** across the declared range — the floor
      (253) through the newest IDEA and PyCharm EAP/RC.
- [ ] No new deprecated or scheduled-for-removal IntelliJ APIs. This is a build
      failure level, not a warning (see
      [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)).
- [ ] `config/detekt/baseline.xml` untouched. It holds exactly two accepted
      findings; regenerating it to make a build pass defeats the gate.
- [ ] No secrets, tokens, conversation transcripts, or absolute personal
      paths in commits.

## Code style

- **Formatting is mechanical, so it is not a review topic**: Spotless/ktlint
  decides how the code looks and detekt decides whether it is likely wrong. Run
  `./gradlew spotlessApply` rather than arguing about it; the JS side is Prettier
  plus ESLint, where `no-eval` and friends are *errors* because the page runs
  under a hash-pinned CSP with no `unsafe-eval`.
- Match the existing tone in `src/main/kotlin/dev/lain/claudejb/`: small,
  cohesive files, top-level KDoc on services and protocol types, expression
  bodies where they read better, no unnecessary mutability.
- **Kotlin idioms first** — prefer `sealed`/`enum` over magic strings;
  serialization at the wire edge only.
- **Threading discipline:** I/O and parsing on `Dispatchers.IO`; UI on
  EDT (`ApplicationManager.invokeLater` or `EDT` dispatcher).
- **No raw CLI scraping.** Reconstruct state from structured stream-json
  fields. `system/local_command_output` is the antipattern.
- **KDoc lines must stay under 120 columns** and must **never** contain a
  literal `/*` inside a block comment — Kotlin block comments nest and the
  parser will report an unclosed comment.
- Public symbols intended for other modules get explicit visibility
  modifiers; everything else is `internal` or `private`.
- Prefer platform APIs (`VfsUtil`, `DiffManager`, `FileEditorManager`) over
  ad-hoc IO when an equivalent exists.

## Running tests

The project uses the IntelliJ Platform Gradle Plugin 2.x with a JDK 21
toolchain (the IDE itself runs on JBR 21).

```bash
JAVA_HOME=~/.jdks/jbr-21.0.11 \
  ./gradlew test koverVerify detekt spotlessCheck verifyPlugin buildPlugin
npm ci && npm test && npm run lint && npm run format:check
```

`test` runs the whole non-UI pyramid — pure JUnit 5 units, headless
`BasePlatformTestCase` component tests, and integration tests driven against the
deterministic `bin/fake-claude` stand-in — because the IntelliJ Platform Gradle
plugin only instruments *its* `test` task with the platform runtime.
`npm test` runs vitest over the **real shipped** JCEF JavaScript; nothing in that
toolchain is packaged. `verifyPlugin` validates against the declared IDE range;
pass `-PlocalIdePath=<dir>[,<dir>…]` to verify offline against local installs.

The RemoteRobot end-to-end suite is separate and opt-in
(`-PuiTest.enabled=true`); see [`docs/UI_TESTING.md`](docs/UI_TESTING.md).

## Running the IDE sandbox

```bash
./gradlew runIde
```

This launches a sandbox IDE with the plugin installed. Use it for manual
verification of UX changes (permission cards, diff tabs, transcript
rendering, command palette). The `claude` binary must be on your `PATH`
or at `~/.local/bin/claude`.

## Commit messages

**Conventional Commits are required**, not encouraged: `commitlint` enforces the
stock `config-conventional` ruleset, with the subject capped at 72 characters and
body lines at 100. Enable the versioned hook once:

```bash
git config core.hooksPath .githooks
```

The hook is advisory if the toolchain is unavailable — deliberately, so it never
becomes a reason to reach for `--no-verify`. Merge and revert subjects are
exempt.

```
feat(agents): give each agent its own transcript
fix(session): drain pendingControl on crash
build: bump kotlinx-serialization to 1.7.3
docs: clarify permission-mode source of truth
```

Short, imperative present tense. Reference issues with `#123` in the body when
relevant. **No `Co-Authored-By` trailer** — this repository does not use one, and
the commits are signed by their author.

## Reporting bugs / requesting features

Use the templates under [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE).
For security issues, follow [`SECURITY.md`](SECURITY.md) instead.

## License

By contributing you agree that your contributions will be licensed under
the GPLv3 license that covers this project (see [`LICENSE`](LICENSE)).
