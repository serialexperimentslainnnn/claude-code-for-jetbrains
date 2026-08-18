# Map of `ui/`

> Part of the distributed map. **Root: [`../../../../../../../PROJECTMAP.md`](../../../../../../../PROJECTMAP.md)**
> — repository-wide commands, invariants and the index of every other directory map live there.

## What lives here

The Swing that the IDE platform genuinely demands — the tool window, the ⚙ menu, the Settings page, the
actions — and the **assemblers** that connect a running session to the browser that draws it.

**Everything the user looks at inside a chat is JCEF**, not Swing: the transcript, the composer, the cards,
the dashboard and the tab bar are the web app in
[`../../../../../resources/jcef/`](../../../../../resources/jcef/PROJECTMAP.md), and the payloads they are
drawn from are built in [`jcef/`](jcef/PROJECTMAP.md). A Swing chat UI existed once and was deleted whole.
The two exceptions are deliberate: **diffs** are the IDE's own (see [`../diff/`](../diff/PROJECTMAP.md)), and
the Settings page is a platform `Configurable`.

## Files, by job

**The tool window and its tabs**

| File | What it decides |
|---|---|
| `ClaudeToolWindowFactory.kt` | The tool window and the ⚙ menu — **no title actions at all**: the six that were there are buttons in the page now. Resolved per project. `activePanel(project)` is the only door onto the chat on screen for callers outside this package, and the strip is found by TYPE among the contents, never through `selectedContent`. |
| `ChatTabsPanel.kt` | The `CardLayout` that holds each chat's panel. **One tab per session, one session per tab** — an agent and a background task are transcripts switched inside a chat's own browser, never tabs — which is what lets the factory's close handler dispose a `claude` process without asking. Pinned views made that false once and are gone. |
| `TabSessionCommands.kt` | Restore, rename, fork, reopen. **Not** the Git conversation: that one has no tab (see `GitChatFeed.kt`) and its find-or-create is `ChatSessionManager.gitChatOrCreate()`. |

**The chat panel and its collaborators**

| File | What it decides |
|---|---|
| `JcefChatPanel.kt` | **A thin assembler.** Forwards session events to the browser and dispatches inbound messages back. New behaviour goes to a collaborator or a JS module, never here. |
| `ChatTranscriptView.kt` | What the one browser paints, and the streaming coalescer. |
| `ChatAgentTabs.kt` | Which agent's transcript is showing. |
| `ChatBridgeRouter.kt` | Inbound dispatch: one web message → one host action. |
| `ChatEditReview.kt` | Accept, reject and restore for an edit. |
| `SessionFeed.kt` | The session → page push. |
| `AttachmentTray.kt` | What is attached right now. |
| `LinkNavigator.kt` · `LinkResolver.kt` | Which text in the transcript is a real, openable destination, and opening it. |
| `BackgroundTaskView.kt` | The background-task rows. |
| `ChatTheme.kt` | The chat's own theme state — the background and accent the panel paints, and Vibe Mode. **Not** a copy of the IDE's colours: those are read from the platform in `jcef/JcefTheme.kt`. |

**Settings**

| File | What it decides |
|---|---|
| `ClaudeSettingsConfigurable.kt` | The platform `Configurable`. Thin: it composes the sections. |
| `SettingsSection.kt` | The seam. **The interface, not another section** — it is what makes the `Settings*Section.kt` glob one file longer than the page has sections. |
| `Settings{Model,Security,Provider,Forge,Executable,Tools,Mcp,Advanced}Section.kt` | One section each, listed in the order they are drawn. `SettingsForgeSection` is the odd one: its field is **not** a `ClaudeSettings.State` field at all — the token lives in the PasswordSafe under its host (`forge/ForgeTokens`) — so it is outside the "every persisted field is claimed by exactly one section" contract, and an EMPTY field **clears** the token rather than meaning "leave it alone". |

**Git — three categories, and they are not interchangeable**

| File | What it decides |
|---|---|
| `GitContextActions.kt` | **Read.** Git context in the ⚙ menu **only** — deliberately no Git Log button anywhere else. The one Git control outside this menu is the chat's own `Git` button, which opens the repository view; history is already discoverable here. |
| `GitPromptedActions.kt` | **The agent writes.** Bounded prompts into the Git conversation; the plugin runs no `git`. Each prompt is *a command plus a list of things not to do*, and the prohibitions are the load-bearing half. |
| `GitIdeMenu.kt` | **The platform writes.** The IDE's own action objects, resolved by id — not wrappers. |
| `GitActionCatalog.kt` | The one list both the page and the executor read, so a button cannot be labelled one thing and do another. |
| `GitIntegration.kt` | The project service behind the Git view: dispatching a catalogue entry by its kind. `gitInit` is the ONE command the plugin spawns itself. |
| `GitChatConversation.kt` | **The project's ONE Git conversation, and the only thing that listens to it.** The session, its turn state, its cards and the payload every page draws — one per project, never per panel. It listens to `ChatSessionManager` too, so a Git chat created by the gear menu's fallback door is not one it never hears about. Created on first *use*; merely *looking* only attaches. |
| `GitChatFeed.kt` | **One page's window onto that conversation — the placement, and nothing else.** The `exec` to paint into, the last string painted (so an unchanged repaint costs nothing) and `show()`, which is per-panel because navigating is a thing that happens to the page whose button was pressed. Everything under its own `cc.gitChat` namespace, and **nothing in it may write to the panel's own `cc.state`, `cc.batch` or `cc.permissions`**. |

**Everything else**

| File | What it decides |
|---|---|
| `OnboardingController.kt` | The install / sign-in / loading screens and the `LoginUi` seam. |
| `SessionDiffAction.kt` | ⚙ ▸ Review This Session's Changes… |
| `ReviewPrompt.kt` | The prompt that review sends. |
| `InfoDialogs.kt` | The platform dialogs that are genuinely dialogs. |

<!-- MAP:GENERATED BEGIN -->
<!-- Generated by scripts/gen-projectmap.py. Everything between these markers is overwritten on the
     next run; the prose outside them is not. Nothing gates this — run the script to refresh it. -->

## Symbols — go to the line, the code is the documentation

Top-level declarations and the members of top-level `object`s. `private` and `override` are not
indexed, and neither are extensions: they are called on their receiver, not on their owner.

| Symbol | Kind | Where | Owns |
|---|---|---|---|
| `AttachmentTray` | class | `AttachmentTray.kt:23` | The chips pinned to the next turn: files, selections and images, wherever they came from — an editor action, the 📎 … |
| `BackgroundTaskView` | object | `BackgroundTaskView.kt:21` | What a background task's tab shows, as transcript rows. |
| `BackgroundTaskView.entries` | fun | `BackgroundTaskView.kt:23` |  |
| `ChatAgentTabs` | class | `ChatAgentTabs.kt:19` | The tab bar this page draws, and everything reached from it: which agents are open, which the user closed, revealing … |
| `ChatBridgeRouter` | class | `ChatBridgeRouter.kt:30` | Everything the web app sends back, routed to whoever owns it. |
| `ChatEditReview` | class | `ChatEditReview.kt:16` | Reviewing and undoing an edit: the read-only diff a permission card shows, and the restore behind a completed one. |
| `ChatTabsPanel` | class | `ChatTabsPanel.kt:47` | Holds the chats and switches between them. |
| `ChatTheme` | object | `ChatTheme.kt:19` | The host's view of the **IDE theme** (light/dark): the surface behind the chat page comes from the platform ([UIUtil]) … |
| `ChatTheme.BG` | val | `ChatTheme.kt:34` |  |
| `ChatTheme.vibeMode` | var | `ChatTheme.kt:40` | 🌈 **Vibe Mode** (a gag toggle): when on, [ACCENT] becomes the rainbow accent. |
| `ChatTheme.setVibeMode` | fun | `ChatTheme.kt:44` | Flips Vibe Mode globally. |
| `ChatTheme.ACCENT` | val | `ChatTheme.kt:49` | Claude coral — links, send, avatar. |
| `ChatTranscriptView` | class | `ChatTranscriptView.kt:23` | What the ONE browser is painting — the chat's own transcript, an agent's, or a background task's view — and the … |
| `ClaudeSettingsConfigurable` | class | `ClaudeSettingsConfigurable.kt:24` | Settings page (Settings ▸ Claude Code) exposing the launch defaults graphically. |
| `ClaudeToolWindowFactory` | class | `ClaudeToolWindowFactory.kt:34` | Registers the right-anchored "Claude Code" tool window. |
| `GitActionCatalog` | object | `GitActionCatalog.kt:22` | What the Git view can do, as data — **one catalogue, read by both the payload and the executor.** The page draws a … |
| `GitActionCatalog.ACTIONS` | val | `GitActionCatalog.kt:108` | Every action, in view order. |
| `GitActionCatalog.byId` | fun | `GitActionCatalog.kt:179` | Looks an action up by the id the page sent back. |
| `GitActionCatalog.isCommitHash` | fun | `GitActionCatalog.kt:200` | True when [hash] has the SHAPE of a Git object name: hexadecimal, [MIN_HASH_LENGTH]–[MAX_HASH_LENGTH] long. |
| `GitActionCatalog.applicable` | fun | `GitActionCatalog.kt:205` | The subset that applies to the given repository state, in view order. |
| `GitActionCatalog.commitActions` | fun | `GitActionCatalog.kt:222` | The subset drawn on each commit of the history rail, in view order. |
| `GitActionCatalog.ideActions` | fun | `GitActionCatalog.kt:225` | The subset the IDE runs itself, in view order — what [GitIdeMenu] turns into a submenu. |
| `GitChatConversation` | class | `GitChatConversation.kt:51` | The project's ONE Git conversation, and the only thing that listens to it. |
| `GitChatFeed` | class | `GitChatFeed.kt:27` | One page's window onto the project's Git conversation — **the placement, and nothing else**. |
| `GitContextActions` | object | `GitContextActions.kt:45` | The tool window's Git entries: where this project's checkout stands, and the two doors into the IDE's own Git UI. |
| `GitContextActions.gearEntries` | fun | `GitContextActions.kt:51` | The gear-menu entries, in menu order. |
| `GitContextActions.menuText` | fun | `GitContextActions.kt:70` | The "recent commits" label. |
| `GitContextActions.popupTitle` | fun | `GitContextActions.kt:77` | The chooser's title: the branch (or the detached-HEAD wording) and the revision `HEAD` points at. |
| `GitContextActions.commitRow` | fun | `GitContextActions.kt:91` | One commit on one line: `a1b2c3d Subject · Author · 3d ago · 4 files`. |
| `GitIdeMenu` | object | `GitIdeMenu.kt:51` | The Git operations the IDE already does better than we ever would — **as the IDE's own actions**. |
| `GitIdeMenu.gearEntry` | fun | `GitIdeMenu.kt:54` | The submenu, for the tool window's gear. |
| `GitIntegration` | class | `GitIntegration.kt:71` | The Git view's **runtime**: it collects what the view draws, and it runs what a button on it asks for. |
| `GitPromptedActions` | object | `GitPromptedActions.kt:35` | The Git entries that **change** something — and the plugin runs none of them. |
| `GitPromptedActions.gearEntries` | fun | `GitPromptedActions.kt:45` |  |
| `GitPromptedActions.commitPrompt` | fun | `GitPromptedActions.kt:78` | Stage and commit, with the message left to the agent — the one thing it is better placed to write than the IDE is, … |
| `GitPromptedActions.revertFilePrompt` | fun | `GitPromptedActions.kt:101` | Restore one file to its committed state. |
| `GitPromptedActions.revertToCommitOnNewBranchPrompt` | fun | `GitPromptedActions.kt:128` | Put the tree back the way commit [hash] left it — **on a branch of its own**, which is the whole request and not a … |
| `GitPromptedActions.revertCommitPrompt` | fun | `GitPromptedActions.kt:157` | Undo one commit by recording another — the additive revert, on the branch the user is on. |
| `GitPromptedActions.INITIAL_BRANCH` | val | `GitPromptedActions.kt:314` | What a repository created from here is called. |
| `InfoDialogs` | object | `InfoDialogs.kt:20` | The ⚙ menu's read-only text views over the session: the agent catalogue, the responder binary's version and the … |
| `InfoDialogs.showBinaryVersion` | fun | `InfoDialogs.kt:23` | /version equivalent: shows the responder binary's CLI version (from `get_binary_version`). |
| `InfoDialogs.showEffectiveSettings` | fun | `InfoDialogs.kt:30` | /config equivalent: shows the effective merged settings (from `get_settings`) as readable text. |
| `InfoDialogs.showAgents` | fun | `InfoDialogs.kt:36` |  |
| `InfoDialogs.formatBinaryVersion` | fun | `InfoDialogs.kt:51` | Formats the `get_binary_version` payload (tolerant of `version`/`binary_version` keys). |
| `InfoDialogs.formatEffectiveSettings` | fun | `InfoDialogs.kt:62` | Renders the `get_settings` payload as a sorted `key: value` list. |
| `JcefChatPanel` | class | `JcefChatPanel.kt:37` | The JCEF tool-window tab content: a THIN assembler that binds one [ClaudeSession] to the embedded web view. |
| `LinkNavigator` | class | `LinkNavigator.kt:24` | Where a link in the transcript goes: the browser, an editor, or the Project view. |
| `LinkResolver` | object | `LinkResolver.kt:38` | Resolves the jump-to-code candidates the transcript detects in model text — **file paths** and **symbol names** … |
| `LinkResolver.isOpenable` | fun | `LinkResolver.kt:66` | Where a jump-to-code link is allowed to point: **inside the project, or inside the user's own home**. |
| `LinkResolver.userHome` | fun | `LinkResolver.kt:72` | The user's home, or null when the JVM doesn't report one (then only the project root is openable). |
| `LinkResolver.isFilePathHref` | fun | `LinkResolver.kt:88` | True when a link's href is a FILE PATH rather than a URL — that is, it carries no URI scheme. |
| `LinkResolver.expandHome` | fun | `LinkResolver.kt:93` |  |
| `LinkResolver.resolvePaths` | fun | `LinkResolver.kt:105` | Resolves path candidates — **files and directories alike**. |
| `LinkResolver.scanForNames` | fun | `LinkResolver.kt:205` | Last resort for a bare name the index does not know: **excluded** folders (a build-output dir like `build/`) are not … |
| `LinkResolver.resolveSymbols` | fun | `LinkResolver.kt:262` | Resolves symbol-name candidates (a function, class, …) to their declaration site via the *Go to Symbol* index. |
| `LinkResolver.displayPath` | fun | `LinkResolver.kt:357` | How the link is written out: relative to the project when it lands inside it (short, and what the transcript already … |
| `OnboardingController` | class | `OnboardingController.kt:31` | Everything the two onboarding cards need from the host: installing the binary, validating a manual path, watching for … |
| `ReviewPrompt` | object | `ReviewPrompt.kt:34` | Asks — **once, ever, per user** — for a Marketplace review, after enough successful turns that the person demonstrably … |
| `ReviewPrompt.TURNS_BEFORE_ASK` | val | `ReviewPrompt.kt:37` | Successful turns before we ask. |
| `ReviewPrompt.REVIEW_URL` | val | `ReviewPrompt.kt:43` | The plugin's Marketplace review tab. |
| `ReviewPrompt.shouldAsk` | fun | `ReviewPrompt.kt:49` | Pure policy: ask only once, and only past the threshold. |
| `ReviewPrompt.recordTurn` | fun | `ReviewPrompt.kt:53` | Pure: the next counter value. |
| `ReviewPrompt.onSuccessfulTurn` | fun | `ReviewPrompt.kt:61` | Counts one successful turn and, at the threshold, shows the one-time review balloon. |
| `SessionDiffAction` | class | `SessionDiffAction.kt:30` | "Review this session's changes" — everything the agent has touched, as native diff tabs. |
| `SessionFeed` | class | `SessionFeed.kt:19` | The per-PROCESS data the dashboard shows: the plan-limit windows, the MCP servers and the binary version. |
| `SettingsAdvancedSection` | class | `SettingsAdvancedSection.kt:12` | The advanced launch flags, all with a neutral default that omits the flag entirely (0 / blank). |
| `SettingsExecutableSection` | class | `SettingsExecutableSection.kt:13` | Where the binaries live and what environment the session starts with — plus the note that has to sit next to the … |
| `SettingsForgeSection` | class | `SettingsForgeSection.kt:28` | The access token the Git view's pull-request and pipeline cards are fetched with. |
| `SettingsMcpSection` | class | `SettingsMcpSection.kt:20` | The JetBrains MCP server (opt-in) and any number of custom MCP servers. |
| `SettingsModelSection` | class | `SettingsModelSection.kt:27` | Model, effort, permission mode and the chat-wide toggles — the top of the Settings page. |
| `SettingsProviderSection` | class | `SettingsProviderSection.kt:19` | Which API provider a session runs against, and that provider's own key. |
| `SettingsSection` | interface | `SettingsSection.kt:23` | One section of the Settings page (Settings ▸ Claude Code), owning its own widgets. |
| `SETTINGS_FORM_WIDTH` | val | `SettingsSection.kt:56` | Fixed content width (CSS px) the form and its wrapping HTML notes are bounded to, so a wide monitor doesn't stretch … |
| `sectionLabel` | fun | `SettingsSection.kt:58` |  |
| `noteLabel` | fun | `SettingsSection.kt:66` | A small, **width-bounded** HTML note. |
| `csvSet` | fun | `SettingsSection.kt:70` |  |
| `CheckboxGroup` | class | `SettingsSection.kt:74` | A row/grid of checkboxes backed by a comma-separated value — the GUI form of a list option. |
| `SettingsSecuritySection` | class | `SettingsSecuritySection.kt:12` | The deterministic tool-call lock's six per-rule switches (see `permission/SensitiveGuard.kt`). |
| `SettingsToolsSection` | class | `SettingsToolsSection.kt:17` | Setting sources, the allowed/disallowed tool grids, and the revocable "Always allow" list — all pick-from-checkboxes, … |
| `TabSessionCommands` | class | `TabSessionCommands.kt:29` | The conversation commands behind the tool window's gear menu — restore, rename, fork, reopen — and how a past session … |

<!-- MAP:GENERATED END -->

## Conventions here

- **No Swing for anything inside a chat.** If it is visible in the conversation, it belongs to the web app.
- **`JcefChatPanel` stays an assembler.** New behaviour goes in a collaborator, a JSON builder or a JS module;
  behaviour added to the panel itself is behaviour nobody else can reuse or test.
- **A menu entry is absent, not disabled**, when its precondition is unmet — with one deliberate exception:
  the Git button stays visible with no repository, because the entry that matters there is the one that
  creates one.
- Gear entries hide themselves. **A feature whose only door is the ⚙ menu is a feature nobody finds**; that is
  why the actions that matter also have a button in the chat's own action row — which is where all six of
  them live now, the tool window having no title actions at all.
- **The plugin runs no `git`.** It prompts the agent, or it invokes the platform's action. Both routes put the
  change in front of the user before it happens.
- A prompted action's text is a **pure function, pinned by a test**: each is a command plus a list of things
  not to do, and the prohibitions are the load-bearing half.

## Minefields here

- **A tab must declare `Content.preferredFocusedComponent`, and it must point at the browser's UI component**
  — the wrapper the JCEF component sits in is not focusable. A raw `requestFocusInWindow()` is refused while
  the platform settles focus. Select the content with focus instead; that is the path a manual tab switch
  takes.
- **`JcefChatPanel` is where the init-order defect actually happened.** A property declared below the `init`
  block that reaches it is null while the constructor runs — it threw there, so no chat could be opened or
  restored at all, and the fields beside it with the same defect stayed silent by reading as null or zero.
  `InitOrderContractTest` scans the sources for it because the compiler only sees the direct reference.
- **`PluginId.getId(…)` is banned.** `PluginId` is a Kotlin class since 2025.2, so it binds to the companion
  and dies with `NoSuchFieldError` on an older IDE. Go through `util/InstalledPlugins.kt`.
- **A platform action id that gets renamed is silently *skipped*** — one fewer menu item and no error
  anywhere. `GitIdeMenu`'s ids are pinned by a test whose second assertion checks Git4Idea is actually loaded,
  because without it every id resolves to null and the first assertion proves nothing.
- **The Git conversation's forced approval is wired per session, not per turn**, so there is no window in
  which the forcing lapses. Its failure mode is silent: a prompted `git push` would simply run. Its cards
  therefore travel with it into the Git view — a view that showed the conversation but not the card would be
  one you cannot finish anything from.
- **Two `ClaudeSession`s reach one browser** (`GitChatFeed`), and only one of them owns the page's own
  namespaces. Writing the Git conversation's transcript, turn state or cards through `cc.state`/`cc.batch`/
  `cc.permissions` overwrites the chat the user is actually reading, from a panel that is not theirs.
- **The Git conversation is the PROJECT's; a panel owns only where it is painted.** One session was always
  true — what was per-panel was the *subscription*, and a page attached only as a side effect of ACTING on
  the chat, so a page that merely looked at the Git view drew an empty pane over a running conversation and
  read as a brand-new one. `GitChatConversation` holds the session, the listeners and the payload; a `View`
  is handed the WHOLE conversation on attach and again on every change. **Never move conversation state back
  into a per-panel field** — the defect returns with the next field somebody adds. Its symmetric error is
  worse: a detaching panel must not end the conversation the other tabs are looking at.
- **A gesture that ends in a nullable chain fails with no exception, no log and nothing on screen.** Three
  orderings in `ClaudeToolWindowFactory` decide that and none is visible at the call site;
  `ToolWindowWiringContractTest` scans the sources for them, because reaching any of them for real needs a
  live IDE and a JCEF browser. It also pins the **one construction site for `JcefChatPanel`**: a second one
  puts two tabs on one `claude` process, and the close handler disposes it without asking.
- `LinkResolver.isOpenable` (project or `$HOME`) is the **open** gate. It is deliberately looser than
  `DiffPresenter.isWithinRoot`, which is the **write** gate. Do not unify them.
- The obvious platform hook for a generated commit message does not fit: `commitMessageProvider` is
  synchronous, so feeding it a model turn means blocking the EDT — and git4idea registers its own provider
  first anyway.

## Neighbours

- The payloads this package builds and pushes → [`jcef/`](jcef/PROJECTMAP.md)
- The page that draws them → [`../../../../../resources/jcef/`](../../../../../resources/jcef/PROJECTMAP.md)
- The session being drawn → [`../session/`](../session/PROJECTMAP.md)
- What the Settings page persists → [`../settings/`](../settings/PROJECTMAP.md)
- Read-only Git state → [`../git/`](../git/PROJECTMAP.md)
- Diffs → [`../diff/`](../diff/PROJECTMAP.md)
