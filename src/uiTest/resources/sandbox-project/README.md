# UI Test Sandbox Project

Minimal project opened by `runIdeForUiTests` so the Claude Code tool window has a
real `Project` context (the chat tabs, the composer, @-mentions, jump-to-code and
diff tabs all need one). Kept tiny and build-less on purpose: opening the plugin's
own repo would trigger Gradle import, long indexing and a trust dialog, making the
RemoteRobot suite slow and flaky.

`src/Sample.kt` is not decoration — `UiTestBase.openSampleFile()` opens it, because
"Add Current File as @-context" has nothing to pin unless a file is open in an
editor. Renaming or moving it breaks `AttachmentChipUiTest`.
