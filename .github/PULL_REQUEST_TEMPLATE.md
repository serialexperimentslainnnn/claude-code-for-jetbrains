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

## Checklist

- [ ] PR targets the `develop` branch (or `main` only for hotfixes).
- [ ] `./gradlew test verifyPlugin buildPlugin` passes locally.
- [ ] `verifyPlugin` is **Compatible** with IU-261 and IU-262 and reports
      no new internal-API usage (`@ApiStatus.Internal`).
- [ ] No new deprecated or scheduled-for-removal IntelliJ Platform APIs.
- [ ] Tests added or updated under `src/test/kotlin/...` for the new
      behaviour.
- [ ] User-visible changes are documented in [`CHANGELOG.md`](../CHANGELOG.md)
      and [`RELEASE_NOTES.md`](../RELEASE_NOTES.md) under `Unreleased`.
- [ ] No secrets, tokens, conversation transcripts, or personal absolute
      paths in the diff or commit messages.
- [ ] Follows the conventions in [`CONTRIBUTING.md`](../CONTRIBUTING.md)
      and the architectural contract in [`CLAUDE.md`](../CLAUDE.md).

## How was this tested?

- [ ] Unit tests (`./gradlew test`)
- [ ] Manual sandbox (`./gradlew runIde`) — describe the scenarios you
      exercised.
- [ ] Smoke test on a real IDE install — describe.

## Notes for reviewers

Anything tricky, follow-up work, or open questions.
