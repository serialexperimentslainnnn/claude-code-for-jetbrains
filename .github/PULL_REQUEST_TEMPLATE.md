# Pull request

## Summary

What does this PR change and why? One short paragraph is fine.

## Related issue

Closes #<issue-number> <!-- or "Refs #..." / "n/a" -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor (no behavioural change)
- [ ] Docs / build / CI
- [ ] Security fix

## Risk and rollback

**Risk:** what breaks if this is wrong, and for whom? (`none` is a valid
answer for docs-only changes — say so rather than leaving it blank.)

**Rollback:** how is this undone once released? Reverting the commit is not a
rollback for a published plugin — a user on the bad version stays there until
they update. If the change touches persisted settings, the transcript format,
or the permission surface, say what happens to a user who already ran it.

## Checklist

- [ ] PR targets the `develop` branch (or `main` only for hotfixes).
- [ ] Commits follow Conventional Commits (the `commit-msg` hook enforces it —
      install once with `git config core.hooksPath .githooks`).
- [ ] `./gradlew test verifyPlugin buildPlugin` passes locally.
- [ ] `verifyPlugin` is **Compatible** across the declared range (253 → 263.\*)
      and reports no new internal-API usage (`@ApiStatus.Internal`).
      The CDN download is unreliable here; use
      `-PlocalIdePath=<dir>[,<dir>…]` with locally-extracted IDEs.
- [ ] `./gradlew detekt spotlessCheck`, `npm run lint` and `npm run format:check`
      pass. These are gated on PRs into `main` only, so a failure otherwise
      lands on `develop` and blocks the next release rather than this PR.
- [ ] Renamed a CI job? `.github/rulesets/*.json` names required checks by the
      job's **display name** — update them in this PR and re-run
      `./scripts/apply-rulesets.sh`, or the gate silently stops applying.
- [ ] No new deprecated or scheduled-for-removal IntelliJ Platform APIs.
- [ ] Tests added or updated for the new behaviour — `src/test/kotlin/…` for
      Kotlin, `src/test/frontend/…` (`npm test`) for anything under
      `src/main/resources/jcef/`.
- [ ] Protocol changes: `./gradlew checkDrift` is green and the baseline in
      `scripts/drift-baseline.properties` matches what was verified.
- [ ] New dependency? Its licence is compatible with GPL-3.0-only and it is
      recorded in [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md) if it
      ships in the artifact.
- [ ] User-visible changes are documented in [`CHANGELOG.md`](../CHANGELOG.md)
      (what changed, for someone debugging) **and**
      [`RELEASE_NOTES.md`](../RELEASE_NOTES.md) (why it matters, for someone on
      the Marketplace page), under the version being prepared. There is no
      `Unreleased` section — `release.yml` publishes the newest `## [x.y.z]`
      block of `CHANGELOG.md` verbatim, so a heading that is not a version
      would ship as the release notes.
- [ ] No secrets, tokens, conversation transcripts, or personal absolute
      paths in the diff or commit messages.
- [ ] Follows the conventions in [`CONTRIBUTING.md`](../CONTRIBUTING.md), the
      architectural contract in [`CLAUDE.md`](../CLAUDE.md), and the recorded
      decisions in [`docs/adr/`](../docs/adr/README.md).

## How was this tested?

- [ ] Unit tests (`./gradlew test`) and frontend tests (`npm test`)
- [ ] Manual sandbox (`./gradlew runIde`) — describe the scenarios you
      exercised.
- [ ] Smoke test on a real IDE install — describe.
- [ ] **UI changes only:** driven with the keyboard alone, with the focus ring
      visible on every control touched. Automated checks catch roughly half of
      real accessibility barriers and none of the judgement calls, so this one
      is not delegable to a tool.

## Notes for reviewers

Anything tricky, follow-up work, or open questions.
