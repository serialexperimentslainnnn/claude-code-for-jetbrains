# UI Test Sandbox Project

Minimal project opened by `runIdeForUiTests` so the Claude Code tool window has a
real `Project` context (composer, @-mentions, jump-to-code, diff tabs). Kept tiny
and build-less on purpose: opening the plugin's own repo would trigger Gradle import,
long indexing and a trust dialog, making the RemoteRobot suite slow and flaky.
