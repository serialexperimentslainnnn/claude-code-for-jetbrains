# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.5] — 2026-05-26

### Added
- Status bar (thinking word + live token count + "Esc to interrupt") moved above the quota bar for better visibility
- Settings: setting-sources, allowed-tools and disallowed-tools fields now have enable/disable checkboxes

### Changed
- Send / Stop / Accept buttons now use the IDE's primary button color instead of a fixed coral, respecting the active theme
- Tool window icon and plugin icon replaced with the official Claude logo (Simple Icons, `currentColor` for theme adaptation)
- `CLAUDE.md` and code comments translated to English

### Fixed
- (fill in)

## [0.1.1] — initial release

### Added
- Native stream-json + control protocol transport (one long-lived process per tab)
- Streaming chat transcript with markdown rendering (bold, code blocks, tables)
- Multi-chat tabs via `ChatSessionManager`
- Permission-gated diff review: Edit/Write proposals shown as in-editor diff tab + inline Accept/Reject card
- `AskUserQuestion` support with multi-select option cards
- Slash-command palette (all commands from `initialize` + client-side `/btw`)
- Model / effort / permission-mode / thinking chips + gear menu
- Multi-prompt queue (send follow-ups while agent works)
- Quota bar + live token counter
- Auto-diff on acceptEdits / bypass permission mode
- Ctrl+O toggle for reasoning blocks
- UI rethemed to follow the active IDE theme (light/dark)

[Unreleased]: https://github.com/lain/claude-code-for-jetbrains/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/lain/claude-code-for-jetbrains/compare/v0.1.1...v0.1.5
[0.1.1]: https://github.com/lain/claude-code-for-jetbrains/releases/tag/v0.1.1
