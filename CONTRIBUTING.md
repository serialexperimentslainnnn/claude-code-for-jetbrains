# Contributing to Claude Code Native

Thanks for considering a contribution. This plugin is GPLv3 and community
driven; PRs that improve correctness, UX parity with the original Claude
Code, or coverage of the stream-json/control protocol are very welcome.

Please skim [`CLAUDE.md`](CLAUDE.md) before writing code — it documents the
architectural decisions and the behavioural contract with the `claude`
binary. Doing so will save a review round-trip.

## Branching model

- **`main`** — released versions only. Tags `vX.Y.Z` are cut from here.
- **`develop`** — default integration branch. Open PRs against `develop`.
- **`feature/*`**, **`fix/*`**, **`chore/*`** — short-lived branches from
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
6. Resolve review feedback. Squash on merge is fine.

## Pull request requirements

Your PR will be merged once:

- [ ] CI is green on Linux (`./gradlew test verifyPlugin buildPlugin`).
- [ ] New behaviour has tests under `src/test/kotlin/...`.
- [ ] If the change is user-visible, [`CHANGELOG.md`](CHANGELOG.md) and
      [`RELEASE_NOTES.md`](RELEASE_NOTES.md) have an entry under the
      `Unreleased` section.
- [ ] `verifyPlugin` reports **Compatible** for IU-261 and IU-262 with no
      newly introduced internal-API usage.
- [ ] No new deprecated or scheduled-for-removal IntelliJ APIs (see
      [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)).
- [ ] No secrets, tokens, conversation transcripts, or absolute personal
      paths in commits.

## Code style

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
JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew test verifyPlugin buildPlugin
```

This runs the JUnit 5 suite, validates the plugin against the configured
IDE channels (currently IU-261 and IU-262/RC), and builds the distributable
zip into `build/distributions/`.

## Running the IDE sandbox

```bash
./gradlew runIde
```

This launches a sandbox IDE with the plugin installed. Use it for manual
verification of UX changes (permission cards, diff tabs, transcript
rendering, command palette). The `claude` binary must be on your `PATH`
or at `~/.local/bin/claude`.

## Commit messages

Short, imperative present tense. Conventional Commits are encouraged but
not required:

```
feat: render strikethrough in transcript markdown
fix: drain pendingControl on session crash
chore: bump kotlinx-serialization to 1.7.3
docs: clarify permission-mode source of truth
```

Reference issues with `#123` in the body when relevant.

If a change was pair-authored with Claude, add a trailer:

```
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

## Reporting bugs / requesting features

Use the templates under [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE).
For security issues, follow [`SECURITY.md`](SECURITY.md) instead.

## License

By contributing you agree that your contributions will be licensed under
the GPLv3 license that covers this project (see [`LICENSE`](LICENSE)).
