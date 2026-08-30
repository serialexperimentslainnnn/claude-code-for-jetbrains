## v5.8.1 — 2026-08-30

**Closing your last chat no longer raises an internal error.** If the chat you closed was the
only one open and you had actually used it, the IDE logged an `Already disposed` error while
tearing the chat's browser down. The chat is now hidden before it is removed, so nothing tries
to lay out a browser that has already gone. Closing a chat behaves exactly as before otherwise,
replacement chat included.

## v5.8.0 — 2026-08-28

**Pick a chat up from your phone.** Remote Control connects a chat running in your IDE to
claude.ai/code or the Claude mobile app, and Claude keeps running on your machine the whole
time — your filesystem, your MCP servers and your project configuration stay the ones in use.
Start a task at the desk, follow it or steer it from the couch, and carry on in the IDE.

**Two ways in, and neither lies to you.** A phone button sits in the chat's button row, left of
the guard's shield, and *Remote control* is in the ⚙ menu. Both wait for the binary before they
light up, so a control that looks on is on. If the request is refused the button turns red and
its tooltip says why, with the same reason spelled out in the chat.

**What it needs.** Remote Control has to be enabled for your Claude account, and on Team and
Enterprise plans an organisation Owner has to turn it on first.

## v5.7.0 — 2026-08-21

**The Sensitive Guard stops being invisible.** It keeps a log of every alert it raises — in the IDE's
safe, per project — and there is now a Guard view in the chat's view row to read it: what matched,
what the rule saw, the verdict, and what let the call through if anything did. Free-text search,
multi-select filters by category and by rule, and a Whitelist button on any entry. How long entries
are kept is a setting.

**Guard rows survive reopening a chat.** They are the plugin's own rows and the binary's transcript
has no record of them, so they are rebuilt from that log and anchored back to the call they judged.
An alert a subagent earned is drawn in that agent's transcript, where the call happened, and not in
the main one.

**The guard gets a mode.** Enforcing refuses, Permissive asks on a card every time, Allow All lets
the call run — and the choice is available per rule as well as for the guard as a whole. Rules are
Enforcing by default and stay that way unless you say otherwise.

**Settings ▸ Claude Code Security is its own page.** The mode of every rule, grouped by category and
foldable, with All Enforcing and All Permissive per group; temporary suspensions shown and endable;
extra credential paths and extra blocked domains; and the whitelist. Any rule can be whitelisted now,
credential and foreign-path rules included — those ask for confirmation first. Whitelists work at
three reaches: every rule, one category, or a single rule.

**A shield in the chat's button row** switches the guard to Allow All for a duration you pick, and
back with one click. It is unlit whenever the guard is not deciding, so it never implies a protection
that is not running.

**The detection rules see more than they did.** Privilege escalation is refused — `sudo`, `su`,
`doas`, `pkexec` and their family, `runas`, `Start-Process -Verb RunAs`, `psexec`, `wsl -u root` —
matched only where the payload executes, so a file documenting `sudo apt update` trips nothing.
"Outside the project" now reads paths inside shell commands, not just a tool's own location argument,
so `cat ~/notes.txt` no longer slips past a rule that `Read /home/you/notes.txt` would have stopped.
Hex and reversed payloads are decoded before they are judged. Destructive orchestration covers
OpenShift alongside `kubectl`, and recovery inhibition covers VSS and APFS snapshots.

**A row that says so when a rule matched and the call ran anyway** — which rule, what it saw, and
what let it through — carrying the undo for whatever is still in force. A refusal names the rule and
says the decision is about that one call: the old wording made Claude generalise from a single block
and give up for the rest of the session.

**Know what your dependencies are carrying.** A Vulnerabilities view checks the project's manifests
against a public advisory database for known CVEs, filters by severity, and hands the findings to
Claude to plan how to solve them — reading your code and checking current advisories first, rather
than proposing a version bump on its own.

**The Git view links out to the IDE's own.** The plugin's second client is gone: Overview opens the
IDE's Pull Requests and Merge Requests windows, which already do that job better. And every view now
redraws in place, so a filter, a scroll position or an open card survives the transcript refreshing
underneath it.

**Take your configuration with you.** Export settings… and Import settings… use one JSON file;
Migrate from another IDE… copies straight from another JetBrains IDE on this machine — you pick the
IDE, the projects, and whether you want the general settings, the guard's, or its alert history. An
exported file never carries your environment variables, because that is where an API key ends up and
a file leaves the machine. A permission mode that would weaken security is refused on the way in.

**Both settings pages were rebuilt to fit the window**, in titled groups instead of one column of
forty rows, with every note re-wrapping as you resize. Settings are now per project and per IDE
installation, so two repositories can disagree about the model, the permission mode or a security
rule; nothing is lost on upgrade, your login stays global, and signing out no longer wipes your
configuration along with your credentials.

## v5.5.0 — 2026-08-19

**This release requires IntelliJ Platform 2025.3.1 or newer, and it is not optional.** From build 262 the IDE
ships its embedded browser as a separate bundled plugin, and a plugin that does not declare a dependency on it
no longer gets those classes at all. The whole chat UI is that browser, so on 2026.2 nothing opened —
`NoClassDefFoundError: com.intellij.ui.jcef.JBCefApp`, every chat, every time. Declaring the dependency fixes
it, and that dependency first exists in **2025.3.1** — which is why the floor is that build and not the first
release of the 2025.3 branch.

So this costs 2025.1, 2025.2 and the very first 2025.3, and it is worth saying why there is no middle option.
Since 4.0.0 the entire interface — transcript, composer, permission cards, dashboard, tabs — *is* the embedded
browser; there is no second, browser-less UI to fall back to, and building one would be building the plugin
twice. The choice was between a plugin that works wherever that browser is declarable, or one that is silently
dead on the newest IDEs. On **2025.1, 2025.2 or 2025.3.0, stay on 5.1.1** — it keeps working, it just stops
receiving updates — or update the IDE: 2025.3.1 shipped in December 2025.

**Every agent gets its own tab, with its own transcript.** A session running agents under agents used to put
all of it in one place: consecutive "Thought process" rows belonging to different agents, interleaved, with no
way to follow any single one. A second row under the chats now lists everything the open chat started —
agents, the agents they started, background tasks — all of it visible at once rather than behind a menu, and
scrollable the same way the chats are. Opening one swaps what the conversation area shows. Closing it hides a
view; it destroys nothing, and the card that started it opens it again.

**And the chat tabs are all one width**, so the row above reads as a strip instead of an accordion of long and
short titles, and nothing reflows when you pick one. A long name ellipsises with the whole of it in the
tooltip, and selecting a chat centres it — which is what makes ordinary use need no scrolling at all.

**Everything that is running, in one diagram.** The Agents, Subagents and Background tasks lists were three
views of the same tree, so finding out whether an agent had spawned anything meant switching view and losing
the parent. They are now a single **Workloads** diagram spanning every open chat, and every node in it is
somewhere you can go.

**A background task keeps its tab and its output after it ends.** The binary stops listing a task the moment
it finishes — which is exactly when its output is worth reading — so the row, the tab and everything it had
printed used to vanish at that instant. Both now survive, and they come back after a restart.

**A chat names itself**, instead of being "Chat 3" for the rest of its life. At the end of the first turn
Claude is asked to title the conversation, and the title is kept *with* the conversation — so it survives a
restart and is never asked for a second time. Until it arrives the tab shows the first thing you actually
typed, one line, cut on a word. A name you set yourself always wins, whenever you set it. All three of these
go through the same place, so they cannot disagree: the tab you are using, the tabs restored when the IDE
starts, and the list behind "Open Previous Session…".

**Everything worth changing mid-conversation is behind the wrench on the composer.** *Chat settings*, in ten
collapsible groups: model, effort and permission mode — the same controls as the pills beside them, acting on
the chat you are in, so the two can never tell you different things — plus the chat toggles, the security lock's
28 rules behind the nine groups they belong to, setting sources, your allowed,
disallowed and always-allowed tools, and the two MCP switches. Anything that can only take effect the next
time a chat starts says so above its group rather than looking as though it did nothing.

**"Always allow" no longer has to be earned one card at a time.** You can grant it in that menu for any of
Claude's built-in tools, without waiting for the tool to ask first. It does not widen the deterministic
security lock: a credential file, a dangerous command, the system temp folder and anything outside your
project still stop and ask you, for an always-allowed tool exactly as for any other.

**When the lock refuses something, you can answer it there — and the answer expires on its own.** A refusal used
to be a dead end: the row told you which rule stopped the call, and the only way to act on it was a trip to
Settings, where the only choice is to turn that rule off *permanently*. The block now carries a **Disable rule**
link with seven durations — 5 minutes, 15 minutes, 30 minutes, 4 hours, 8 hours, until the IDE closes, or for
ever — and five of them heal themselves, so the lock ends up open for less time than it was before this existed.
Opening the menu commits to nothing: each entry is the action, so there is no default to accept by reflex.

**Nothing opens the lock without you saying so, once, about one command.** Disabling a rule has never granted
anything silently — it turns a refusal into a question you answer, every time, whatever permission mode you are
in — and the one implicit pass that remained is gone: a tool marked "Always allow" used to skip that question,
so a single click on a `Bash` card quietly opened every command `Bash` can run. On a lock card, "Always allow"
is now about **the command**: answer it on a `terraform destroy` card and you have pre-approved that exact
command, not `terraform destroy -auto-approve`, not the tool, and not anything else the rule stops. It lasts
only while that rule is open, so re-enabling the rule — or letting the suspension run out — takes it with it.

**And the button rows never run off the edge again.** In a narrow tool window whatever does not fit is
collected behind a `⋮` at the end of the row rather than being painted somewhere you cannot reach. Send is
never collected, and never shrinks to make room for anything.

**The line above the prompt box lines up.** Status, model and working directory, your account, the plan bars
and their reset times are five rows sharing one grid of four equal columns at one size, so the figures sit
under each other instead of drifting row by row. Too narrow for four, and **Show more** folds the last two
columns away across all five rows at once — a bar and its own reset time can never be separated.

**Attach ▸ Files… and Directory… browse your project inside the menu now**, as a tree that unfolds where you
are, rather than opening a file dialog on top of the IDE. Pick as many as you like and press *Done*; marking a
folder marks everything under it and tells you how many that is *before* you commit to it. It offers what the
IDE considers yours — your `.gitignore` and the project's excluded folders are honoured, so `build/` and
`node_modules/` are simply not in the list — and where a folder is too large to offer whole, it says so
instead of quietly attaching part of it.

**Your branch is in the ⚙ menu, and so is your recent history.** The tool window's gear menu now names the
branch you have checked out in its own label, so *which branch is Claude working on* is answered without
opening anything. Behind it: your last twenty commits — hash, subject, author, age, how many files each
touched — and the Git history of the file you have open. Both hand you to the IDE's **own** Git Log rather
than drawing a second, worse one inside a chat panel. It is strictly **read-only**: nothing here moves a
branch, rewrites history or talks to a remote, and a test fails the build if that ever stops being true. On
an IDE with the Git plugin disabled, or in a project that is not a Git working copy, the entries are simply
not there.

**And now Git can change things — because the plugin asks Claude to, and never does it itself.** There is a
**Git** button in the chat's own button row; it opens the repository view, which holds a conversation of its
own *about* the repository — so none of this plumbing lands in the chat you are working in, and none of it
makes you leave that chat either. On a project that is not a repository yet, opening it asks to create one
right away — `git init -b main`, so you start on `main` rather than on whatever Git still defaults to. The same
menu offers **Initialize Git Repository**, **Commit Changes with Claude** and **Revert This File with Claude**,
and none of them runs a command: each writes a prompt and lets the agent do the work, which means the command
appears in front of you in an approval card *before* it runs, and you can answer back — *"squash those two"*,
*"not that file"* — instead of getting one shot at a button. That conversation is always approved by hand,
whatever permission mode you are in and whatever you have marked "Always allow": the plugin started the turn,
so it does not inherit permissions you granted for your own work. It only starts the first time you open the
Git view, never before — it is a second `claude` process with its own cost, and nobody should pay for one
they do not use.

**Everything else Git is under ⚙ ▸ Git Operations, and those buttons are the IDE's own.** Branches and new
branch, pull, fetch, push, merge, rebase, stash, unstash and the commit dialog — the real dialogs, with their
real shortcuts and their real enablement, one menu away instead of buried in the main menu bar. They are not
asked of Claude on purpose: an interactive rebase is a screen with a branch list, a conflict view and an undo,
and no chat card improves on that. What is worth asking an agent is the part it knows and a dialog cannot —
*why* the change was made.

**The dashboard has a Git view with the same entries in one place.** Where HEAD is, what is modified right now,
the recent commits with their subject, author, age and how many files each touched, and the actions that apply
to the state you are actually in — *Initialize* only on a project without a repository, the per-file revert
only while a changed file is the one in front of you. The buttons come from the same catalogue the plugin
dispatches on, so a button cannot be labelled one thing and do another. On an IDE with the Git plugin disabled
the view is simply not drawn.

**That history is one graph with branch lanes**, not a commit list beside a separate branch map — two pictures
of the same history asked you to hold both at once. Every line and every fork in it comes from real parents
and real refs; nothing is guessed, and where a line continues past the oldest commit shown it says so rather
than stopping in mid-air. It reads every branch, remote branch and tag rather than only the one you have
checked out, because a fork you can see only one side of cannot be drawn at all. Colour never carries anything
on its own: a branch is a text tag on its row, and a merge says the word.

**And GitHub and GitLab answer for the branch you are on** — the pull or merge requests open from it, and its
most recent CI run, beside the rest of the repository picture. It is read-only and entirely opt-in: nothing
appears, and nothing asks you to configure anything, until you paste an access token under Settings ▸ Claude
Code ▸ **Git forge**. That token goes into your OS keychain and is kept **per server**, so a company GitLab and
gitlab.com are two separate credentials and one can never be sent to the other; emptying the field revokes it.

**Diff History is gone.** The **Restore** you actually use was never in it — it is on the edit's own card in the
transcript, and it stays there. The panel was a second, worse door onto the same thing, and it hid the chat tabs
whenever it was open. If what you want is everything a long run changed, that is ⚙ ▸ **Review This Session's
Changes…** below. Gone with the panel is **Roll back all changes**, deliberately not replaced: without Git it
also reverts what *you* typed between Claude's edits, with nothing to tell them apart, and with Git your IDE's
Local Changes does the job better and lets you undo the undo.

**⚙ ▸ Review This Session's Changes… opens everything a run touched, in the IDE's own diff viewer.** One list,
every file, against one base — your working tree against the last commit, or your branch against where it left
the default one — so a long session is reviewed the way a pull request is, rather than by scrolling back
through cards. Where the "before" side cannot be reconstructed honestly the pane says so in its
own title instead of showing you something plausible — *New file*, *Binary file*, *Not available (too large or
restricted)*, *Changed on disk since the diff was taken*. A fabricated left-hand side in a review tool is worse
than none, because nothing on screen tells the two apart.

**And the plan you approved is in the dashboard.** In plan mode the plan stopped being visible the moment the
conversation moved on; there is now a **Plan** view holding the current one, rendered as the markdown it is.
The button appears only when a plan actually exists, and the plan is re-read when you approve one and whenever
a turn finishes — a plan is written *by* a turn, so that is when it can have changed.

**Your settings moved into the IDE's password safe.** They lived in `.idea/claude-code.xml`: per project, in
the clear, and committable — including the environment block, which is where an API key or a credentialed
proxy URL ends up. They are now one encrypted document in the same store as your sign-in, shared by every
project. Existing settings are adopted automatically on first run.

**And your sign-in stays where it always was — in that same safe, and it survives a reboot.** The credential is
held in your OS keychain through the IDE's password safe, never in plaintext on disk, and when the short-lived
half of it expires overnight the plugin renews it without a browser, a terminal or you. If you authenticate
with an Anthropic API key instead, that key lives in the same place and has nothing to expire.

**An access token running out mid-session no longer asks you to sign in again.** Nothing was signed out when
that happened: the short-lived half of the credential had expired and the renewable half was sitting right
there, but the two failures read almost alike in the binary's own error text, so both raised the sign-in card
and it looked as though the session had lost your account. They are told apart now — the text is classified,
and whether a renewal is actually possible is read from the credential safe rather than guessed from the
wording — so an expiry that heals itself gets a line saying the turn did not complete and to send the message
again, and only a genuinely missing identity brings up the card. Either way the message is never re-sent for
you: what a half-finished turn already did is yours to look at first.

**The chat is noticeably lighter.** It felt heavy because it was doing a great deal of work nobody asked for:
the tab bar and the dashboard rebuilt their entire contents on every update from the plugin — several times
per turn, including on updates that changed nothing you could see — and the dashboard did it even while it was
hidden, laying out and measuring a diagram for a panel nobody was looking at. Both now redraw only when what
they draw has actually changed, so a tab bar no longer rebuilds itself under your pointer and the dashboard
does no work while it is closed. Rules nothing could reach came out of the stylesheet at the same time, and a
sizeable slice of the chat panel was split into smaller pieces.

**The conversation now uses the whole width of the tool window.** It was capped at a fixed column — the right
call for a page you read across a monitor, the wrong one for a panel whose width you already chose by
dragging it, where everything past the cap was margin. Diffs, tables and command output are what you get back.

**The dashboard no longer loses your place.** It used to take the transcript's slot, and a scrolled view that
stops being shown forgets where it was, so coming back from the dashboard dropped you at the top of a long
conversation. It now sits *over* the transcript, which stays exactly where you left it.

**Chats work under Remote Development.** The chat is an embedded browser, and on a remote setup that browser
runs on your machine while the page it is meant to show is served from the backend — so it resolved nothing
and you got an empty panel. The plugin now falls back through several ways of delivering that page, and if
none of them can reach you it stops guessing and tells you plainly which port to forward and the exact `ssh`
command that does it. That is a message the browser's own network error was never going to give you.

**Long sessions stay light.** The transcript keeps a bounded amount of scrollback in memory now and says so
in a line at the top when older rows have been dropped. **Nothing is lost** — the whole conversation is on
disk in the `claude` binary's own session file, and "Open Previous Session…" reads it back in full.

**And Workloads only shows you finished work while it is still interesting**, on a window you pick — five
minutes through four hours, or All, if you want the lot. Anything still running is always there whatever its
age, and a finished agent stays as long as something underneath it is still going, so the diagram never drops
a parent out from under live work.

**Claude now knows it is talking to you through an IDE.** Three things it cannot work out from the protocol
are appended to its instructions when a session starts: that the transcript is a real interface and not a
terminal, so terminal-shaped output is wrong here; that its edits become a diff you review and its file paths
become links you click; and that a deterministic guard may refuse a call outright, so a refusal is an answer
rather than something to work around. It is fixed text — no machine name, no environment value, nothing from
your project — and it is not a security control: nothing in it softens a rule or explains how to get past one.

**Fixes:** an agent you cancelled, or one the session limit cut off, kept the running animation for the rest of
the session — both leave a transcript with no finished turn at the end, which read as work still in flight, and
unlike a genuinely open turn nothing further was ever going to arrive to correct it (155 of the 672 agent
transcripts on one machine end one of those two ways); with many chats open the tabs could not be scrolled at all (a vertical wheel does not move a
horizontal row); the loading screen covered the chat tabs, so you could not switch chats while one was
starting; `/btw` never showed you an answer at all — a side question is answered alongside the conversation
and the transcript deliberately ignores anything that is not the main run, so the reply was dropped every
time, and it now arrives as a note under your question, with the note saying so if there is no answer;
opening a new chat looked like the plugin reloading, because the tab was shown before its page existed and
you watched the whole interface assemble itself; a button pressed while your chats were being restored did
nothing whatsoever, *New chat* among them; closing an agent's tab could shut down the chat that started it,
leaving that conversation on screen over a dead process and dropping it from the chats restored next
startup — which was also what drew a chat twice in the diagram; a nested subagent showed as running for
ever; agents that were working showed as failed while a chat was being restored, and every agent of every past
session came back red; hovering a tab showed the agents of whichever chat you were in rather than that one's,
and that row is no longer hidden behind a hover at all;
a restored chat showed the binary's own bookkeeping — task notifications, the caveat preamble, a `/compact` —
as things you had said; every agent was also listed as a background task, a second nameless row whose
"output" was pages of the agent's own internal records; the same finished task was green in one view and grey
in another; the Chat / Session / Workloads buttons floated over the transcript you were reading; and in a
resumed or forked chat a tool call could be filed under an agent it did not belong to, taking everything
after it inside that agent as well.

**If you verify what you install, the keys have changed.** The tag and the `.asc` beside each download are
signed by a new key, certified by two hardware keys whose private halves have never existed as a file.
Everything needed to check that is attached to this release as **one** file, `trust-chain.asc`: the signing
key and both keys that vouch for it, together — because a chain is imported whole or it is not imported at
all. Import it and verify exactly as before.

The single key file that used to live in the repository is gone. It endorsed nothing you could follow — the
keys that had certified it no longer exist — and it was not the key that signed 5.1.1 either, having been
replaced in the tree after that release went out. A key file that verifies nothing is worse than no key
file, because nobody re-checks it. From now on every release carries the chain that was current when it was
cut, so it stays verifiable long after that key has been retired.

### Upgrade notes — coming from 5.1.1

**Check your IDE first.** 5.5.0 needs **2025.3.1 (build 253.29346.138) or newer**. On 2025.1, 2025.2 or the
first 2025.3 the Marketplace will not offer you this version; 5.1.1 stays installed and stays working, and it
is the last version for those IDEs. On **2026.2 the upgrade is the fix** — 5.1.1 cannot open a chat there at
all, so if that is where you are, this release is the whole point.

**Your settings move themselves, once, on first launch.** The plugin reads the old `.idea/claude-code.xml` for
the project you open, writes it into the IDE's password safe, and only then removes the file — in that order,
so a safe that refuses the write leaves your configuration exactly where it was rather than nowhere. You do
not have to do anything, and you should not have to re-enter anything.

Three consequences worth knowing before you open the IDE:

- **Settings are now shared by every project, where they used to be per project.** If you had deliberately
  different settings in two projects — a different model, a different permission mode, a different environment
  block — they no longer both survive: the first project you open after upgrading is the one whose settings
  become the shared ones. If that matters to you, note down what the others had before you upgrade.
- **The old file was plaintext and committable, and the migration cannot un-commit it.** If
  `.idea/claude-code.xml` was ever committed or shared with an environment block in it, treat anything that
  was in that block — an API key, a token, a credentialed proxy URL — as exposed: rotate it, and remove the
  file from the history. The move stops it happening again; it cannot undo what already left.
- **Two IDEs open at once now share one set of settings.** They are stored per user, not per IDE, so a change
  made in one is a change for both. Each change is applied to the document as it stands at that moment rather
  than to a copy read earlier, so the two cannot silently overwrite each other's fields; and the settings page
  asks the store again every time you open it, so it shows what is stored rather than what this IDE read when
  it started.

**If the keychain is not up yet, nothing is lost.** A settings read that *fails* is not read as "no settings":
the plugin declines to save over a configuration it could not read this run, rather than quietly consolidating
defaults on top of it. Unlock your keychain and restart the IDE.

**Everything else carries over.** You are not signed out — the credential is untouched. Your chat history is
unaffected, because it has always been read from the `claude` binary's own session files rather than stored by
the plugin, and your open tabs are restored as before. The agent, subagent and background-task tabs are new
views over data the binary was already writing, so past sessions get them too.

## v5.1.1 — 2026-08-10

**The plan limits kept updating only when you talked to the agent.** The poll stopped whenever the chat was
not on screen — a collapsed tool window, or another tab selected — so a limit could reset, or fill up from
another device, and the bars went on showing the last figure they happened to catch until something made you
send a message. They now refresh every 30 seconds regardless of what you are looking at.

**And the bars say how long each window has left** — `4h 18m`, right after each percentage, on that window's
own line. 90% with eight minutes to go and 90% with six hours to go are not the same situation, and until now
only the dashboard told you which one you were in.

## v5.1.0 — 2026-08-10

**Older models are selectable again.** The model picker has an **Other models** group with the previous
generations — Opus 4.8 through 4.0, Sonnet 4.6 through 4.0, Sonnet 3.7 and 3.5, Haiku 3.5. It stays collapsed
so the current models are still one click away, and opens on its own if you have an older model selected. If
your plan doesn't include the one you pick, the plugin tells you and puts your previous model back rather than
leaving the chat stuck on something every message would fail on.

**Your plan limits now sit in their own row, right under the status line** — one labelled bar per window
(*Current session*, *All models*, and any per-model limit), blue while you have room, amber as you get close,
red near the cap. They used to be small dots tacked onto the end of the status line, which meant that in a
narrow tool window the limits closest to running out were the first to wrap out of sight. The row now takes
the full width of the panel whatever size you've dragged it to, and reflows instead of overflowing.

**Fable usage is reported.** Per-model limits — Fable's among them — live in a separate list the plugin was
walking past, so `claude`'s own `/usage` showed a Fable row and the plugin showed nothing. They're now read
and shown like any other window, under the name the API gives them, whether or not the CLI decides to
pre-package them for us.

**No more "quota at 100%" when you have barely used any.** A window that was genuinely at 1% was being read
as if it were full, which tripped the near-the-limit warning and popped an IDE notification saying your plan
was spent. It fired most reliably right after a limit window resets — the moment you have the *most* quota
left. The percentage is now read on the scale the API actually sends, and the dashboard and the notification
can no longer disagree.

## v5.0.1 — 2026-08-10

**You should stop having to sign in every morning.** Your login was being stored properly all along — in your
OS credential store, through the IDE's own password safe (KWallet or GNOME Keyring on Linux, Keychain on
macOS, Credential Manager on Windows). What was expiring was the token inside it: Claude issues one that lasts
hours, so a restart the next day found a credential that had gone stale, and the plugin asked you to sign in
again rather than renewing it.

It renews it now. The longer-lived half of your credential — the part good for weeks, and refreshed every time
it's used — is handed back to the `claude` binary, which mints a new token without a browser, without a
terminal and without you. Nothing about how it's stored changes: the credential still lives encrypted in your
OS store and never sits in plaintext on disk. In practice you'll now only be asked to sign in after a long
idle period, or if Anthropic invalidates the session.

**Which sign-in this is about:** the **subscription** one (Claude Pro or Max — the *Sign in* button and the
account row in the dashboard). That is the credential that carries a token with an expiry date on it. If you
authenticate with an **Anthropic API key** instead, nothing here changes for you and nothing here was broken
for you: an API key does not expire and has nothing to renew, it is kept in the same OS-backed store, and it
already survived restarts.

## v5.0.0 — 2026-08-05

**Nothing you use changes.** This is a major because the *project* changed, not the product: the whole
repository was taken through a compliance pass — security, licensing, accessibility, release process — and the
code had to change to pass it. Your chats, settings and sessions carry over untouched.

**♿ The plugin now talks to screen readers.** The chat streams without ever moving focus, which meant that if
you use a screen reader, a turn just went quiet — no signal that Claude had started, finished, or was waiting
on you to approve a tool. There's now a live region that announces exactly that, including when a permission
card appears. And every control that had lost its focus outline has a visible focus ring again, including in
Windows high-contrast mode. If you drive the IDE from the keyboard, this is the release where the plugin stops
losing you.

**🔒 A written threat model.** The sensitive-data lock has always been deterministic Kotlin the model can't
argue with — but until now nothing said what it defends *against*. That's written down now, including the
uncomfortable part: we don't try to detect prompt injection, because nobody can do that reliably. We assume it
succeeds and put the control where it stops mattering — the lock judges the *tool call*, never the reasoning
behind it. A perfectly manipulated model still has to ask to read your SSH key, and still gets the same
answer.

**🧹 Seven security warnings that were never about you.** The plugin's build kept a copy of Anthropic's SDK as
a protocol reference — it's never executed and has never been part of what you install. It was declared
wrongly, so every audit flagged its dependencies as if they shipped. Declared correctly now: zero findings in
what actually reaches you, and the claim is verifiable in one command rather than asked for on trust.

**📄 Licences ship with the plugin.** The attribution for the libraries bundled inside it now travels inside
the artifact, where it belongs, instead of only living in the repository.

**🔖 One more thing, said out loud:** a released version number is now final. Three earlier releases were
re-cut under the same tag, which meant two people could have different files and both believe they had the
same version. From here, a mistake found after release gets a new version number.

**🤖 And this is the first release published by the project's own pipeline** rather than from a laptop —
built once, signed, and released through a gate that checks the tag really came from `main` before any
credential is even in scope.

Verified **Compatible** across the whole supported range (2025.1 → 2026.2) by the plugin verifier in CI.
677 backend tests and 54 frontend tests, all green.

---

## v4.4.1 — 2026-07-29

**🔑 Fixed: `/login` never actually opened the terminal.** Signing in from the chat always ended on "run this command yourself in a terminal" — the plugin was calling IDE terminal APIs that no longer exist in current IDEs (they were removed after 2025.2), and because each lookup failed quietly, nothing showed up in the log to explain it. `/login` now opens a real terminal tab and runs the sign-in there, on every supported IDE version.

If the terminal can't be opened at all — say you've disabled the bundled Terminal plugin — the plugin now falls back to signing you in **natively**, with no terminal involved, instead of giving up. The manual command is only ever shown as a genuine last resort.

**🔁 Fixed: reopening a past chat lost the command code blocks.** Restored conversations rendered `Bash` and other command calls in the old plain-text style — you couldn't copy the command, and its output wasn't a code block either. Reloading a session now produces exactly the same cards a live turn does.

**⭐ Asked once, and only once.** After 25 successful turns, a single IDE notification asks whether you'd leave a review on the Marketplace — and then never asks again, whatever you click. No dialogs, no "remind me later", nothing that interrupts your work. Failed or interrupted turns don't count, so you'll only ever see it if the plugin has actually been working for you.

**📝 Listing rewritten**, with a few stale claims corrected along the way — it advertised a 2025.2 minimum when the plugin actually supports 2025.1+, and documented a couple of shortcuts that had since changed.

Verified **Compatible** on IC-251, IC-252 and IU-262 — including the exact build the `/login` regression was reported on.

---

## v4.4.0 — 2026-07-28

**🔓 Security toggles — Settings ▸ Claude Code ▸ Security.** The five rules behind the plugin's deterministic sensitive-data lock (credentials, dangerous commands, and the three foreign-territory checks — another user's home, network/UNC mounts, foreign WSL drives) can now be switched off individually, if you specifically need to. They're all **ON by default**, so nothing changes unless you go looking. Turning one off is never a silent allow: the lock still watches for it, it just shows you a permission card instead of blocking automatically — every time, for every caller, MCP servers and Skills included. Every block or prompt from the lock now also tells you exactly where to change it.

**🛠 Fixed: several of Claude's own native tools were treated like a blocked MCP server.** As the CLI grew its own orchestration surface — background tasks, cron jobs, worktrees, and more — the plugin's allowlist of "trusted, first-party" tools hadn't kept up, so calls from tools like `TaskCreate`, `CronList`, or `EnterWorktree` were silently hard-denied instead of asking, exactly as if they were a blocked third-party MCP call. The allowlist is now current with the CLI's real tool set.

---

## v4.3.3 — 2026-07-27

**🧬 The model picker shows the version now — and picks Opus for you.** The list of models is read straight from the binary (it always was), but it used to label each one without its version — "Opus (1M context)", "Sonnet" — so you couldn't tell Opus 4.8 from Opus 5. Every model now shows its version ("Opus 5 with 1M context", "Sonnet 5", "Haiku 4.5"), in both the composer and Settings. The vague "Default" entry — which was just Opus listed a second time, with no version — is gone, and a fresh install defaults to the concrete Opus tier and stays there. A stale hardcoded "Opus 4.8" label that lingered on the pill is gone too.

**Maintenance:** protocol baseline refreshed to the latest `claude` 2.1.220 / SDK 0.3.220 (no protocol changes).

---

## v4.3.2 — 2026-07-23

**⌨️ The command you ran, right on the card — no need to expand it.** A `Bash`/PowerShell/MCP-exec call now shows the exact command as its own copyable code block, right under the header, visible whether the card is collapsed or open. The title no longer crams the whole command into the tool name — it just tells you which tool ran — and a command call gets its own distinct look (a left accent) so it stands out at a glance.

**🎨 Syntax highlighting for diffs, and for Read/Write/Edit output.** File content and coloured diffs in a tool card are now syntax-highlighted from the file's extension, on top of the existing added/removed line colouring.

**🛡 Fixed two false-triggers in the sensitive-data lock.** An everyday `Edit`/`Write` touching a line with a `//` comment could get silently **denied outright** — the lock's UNC-network-path check mistook the comment's leading `//` for a Windows network share, and a foreign-territory hit denies regardless of how trusted the tool is. And a `Bash` command that assigned a shell variable containing `$`/`${...}` (e.g. `k=${OTHER}/x`) could **crash the permission check entirely**, leaving that call stuck with no response ever sent back. Both are fixed; neither required weakening what the lock actually protects.

**Fix: the plugin now starts on a WSL project under `/mnt/c`.** WSL2 exposes the Windows `C:` drive over the 9p protocol, which the network-drive detector treated as a remote mount — so the deterministic security layer, which refuses to launch an agent rooted on a network share, was blocking perfectly normal WSL projects on `C:`. `/mnt/c` is now correctly recognised as the local Windows disk (every other `/mnt/*` drive stays foreign, as intended).

---

## v4.3.1 — 2026-07-14

## 🛡 Deterministic sensitive-data protection

A new permission-layer control evaluates **every** tool call before it can be auto-approved. It is deterministic and enforced outside the model: the classification and verdict are the plugin's, independent of anything the model or a prompt injection can say.

**What it covers.** Calls that touch credential or key material — SSH/GPG keys, cloud and cluster credentials, database and shell-history secrets, browser and password-manager stores, crypto wallets, and the access tokens of well-known AI agents and code hosts. Patterns match by structure, so the same rule covers Linux, macOS, Windows (`C:\Users\…\.ssh`) and WSL (`/mnt/c/Users/…\.ssh`). Credential-dumping and exfiltration commands (secret exports, reverse shells, offensive tooling) are covered too, evaluated after resolving symlinks and `..` on disk and after normalising common shell obfuscation (broken quotes, `$IFS`, a path hidden in a variable, a base64 payload piped to a shell).

**How it decides.** The agent's own tools require an explicit permission card whenever a call is flagged — **including in `acceptEdits` and `bypassPermissions`**. MCP servers and Skills are denied access to that material rather than prompted. Access that reaches another user's home, a network or UNC mount, or a foreign WSL drive is denied for every caller. The blacklist is configurable additively (you can widen it, not narrow it), and a session will not start when the project is located on a remote or network-mounted drive.

**Scope.** Detecting a path concealed inside an arbitrary shell string is best-effort and can be widened over time; the enforcement of a match, however, is absolute and cannot be overridden by the model. See `SECURITY.md` for the full model.

## 🔗 Jump to code, straight from the conversation

**Claude names a file, you click it, you are there.** The conversation stops being a wall of text you have to translate back into your project.

**On tool cards.** A file tool now names its file **the way you think about it** — `Read(app/api/routes/auth.py)`, relative to your project, not a bare `auth.py` that tells you nothing about *which* one. And it is a link: it opens the file in the editor **at the right line** and selects it in the Project view, so you can see where it lives.

**In Claude's own words.** Paths (`src/Foo.kt`, `a/b.py:42`, `~/.claude`), **directories** (`build/` — revealed and expanded in the Project view, or opened in your file manager when they live outside the project) and **symbols** (`PermissionBroker` → straight to its declaration) all become links. Even the way developers actually cite a file works: **`app.css:190`**, a bare name and a line, resolves through the IDE's file index — and through a bounded on-disk scan for *excluded* folders like `build/`, which no index knows about. Archives reveal in the tree instead of opening a useless binary buffer.

**And it never lies to you.** The transcript can only *guess* what is a path or a symbol — so nothing is linked on a guess: the IDE confirms every candidate first, and links only what it can resolve **unambiguously**. Two files named `app.css`? No link at all, rather than a jump to an arbitrary one. A path that does not exist stays plain text. A link is never dead, and never takes you somewhere you did not ask for. Symbols resolve through *Go to Symbol*, so this works in **every** JetBrains IDE, not just the Java/Kotlin ones — and a link can only ever point inside your project or your own home, never at `/etc/passwd`, never at another user's files, not even through a symlink.

### Also in this release

**💾 The IDE sees Claude's writes immediately.** The virtual file system was only refreshed at the *end* of a turn, so until Claude went idle the editor showed stale contents — and a link to a file Claude had just written opened nothing at all, because the IDE did not know that file existed yet. Every successful write now refreshes at once: by exact path for `Edit`/`Write`, and by re-scanning the project tree after a `Bash` command or a file-mutating MCP tool, which can change anything. Newly *created* files are picked up too (refreshing a file the VFS has never heard of is a no-op, so its parent directory is re-scanned as well).

**💬 Fixed: a chat tab could come up unusable — the composer refused to take focus.** A new tab (and sometimes the tabs restored at startup) gave you a chat you could not click into; the only cure was closing and reopening the tool window. And even when keystrokes did arrive, a fresh tab showed **no caret**. Both are gone: the tab now tells the platform where its keyboard focus lives and lets the IDE hand it over, and the web view is told it has the focus once the chat actually exists — which is when there is a caret to paint.

---

## v4.2.0 — 2026-07-08

**Protocol upgrade to `claude` 2.1.204 / SDK 0.3.204**, plus a new dashboard card.

**🗂 Background tasks in the session dashboard.** The plugin now understands the binary's `background_tasks_changed` signal and shows a **Background tasks** card (with **Stop**) listing everything running in the background. It's a *level* signal — the full live set is re-sent on every change — so unlike the Subagents list it can never get stuck showing work that already finished.

**🔁 Retry progress for `/btw`.** Progress for long-running side questions is now recognised: an API retry is surfaced as a "Retrying (attempt n/m)…" notice instead of being silently dropped.

**🧠 Fixed: empty "Thought process" on newer models.** Opus 4.8 emits **redacted** thinking — no reasoning text at all, only a signature. The plugin was opening an empty "Thought process" fold for it that never filled. Now there's simply no fold when there's no reasoning to show.

**🔌 Fixed: the MCP servers card layout.** The *Reconnect* button and the enable/disable switch overlapped each other, and the switch's knob painted on top of its own label. The actions row now lays out correctly, the switch is a real switch, and long server names ellipsize instead of pushing the buttons out of the row.

- Models the new `system/background_tasks_changed` and `system/control_request_progress` messages.
- Triages the new `list_models`, `get_plan` and `get_workspace_diff` control requests (thin-client only; the plugin reads its model catalog from the `initialize` reply).
- Backward-compatible with older binaries.

---

## v4.1.0 — 2026-06-27

This release folds in everything since v4.0.3 — the intermediate 4.0.4 / 4.0.5 builds were never published separately, so all of their fixes ship here.

**✨ Editable diff review for edits.** When Claude asks to Edit/Write/MultiEdit a file, the plugin now **auto-opens an editable diff** in the IDE editor (Current | Proposed) on the permission request — not just in acceptEdits/bypass mode. You can **tweak the proposed content right in the editor** before approving; **Accept writes your edited version** (the tool input is re-encoded so the binary writes exactly what you left), and the diff **closes automatically** on accept/reject. The transcript's inline diff and **"View diff"** reflect what was *actually* written (your edit), not Claude's original proposal. Fail-safe: if you change nothing — or the platform renders the proposed side read-only — Accept writes Claude's original proposal, never a wrong write.

**🩹 Read-only diff replaces per-line checkboxes on edit permission cards.** The old hunk-by-hunk partial-acceptance UI (a checkbox per changed region) rendered as a confusing checklist and let you apply an incoherent subset of an edit — a reliable way to produce broken code. Edits are now **atomic**: the card shows a colour-coded diff (red removed / green added) and you accept or reject the whole change.

**🛠 Broad bug-fix + UX pass.** Protocol re-baselined to `claude` 2.1.193 / SDK 0.3.193 (models the new `system/informational`, `model_refusal_no_fallback`, `worker_shutting_down` subtypes).

- **Interrupt** actually stops the turn now — Esc / Stop clears the turn on the binary's ack/timeout instead of looping "Interrupting…" forever; queued prompts and pending permission requests are flushed/denied.
- **Chat dead on first open** self-heals (the web app retries until the bridge exists; the host reloads the page if it doesn't come alive) — no more closing & reopening the tab.
- **User prompts render verbatim** (never as Markdown); the code-block **Copy** button works; duplicate/out-of-order **"Thought process"** fixed.
- **Menu flicker + de-selection** while streaming fixed; single ✓ in prompt menus; Esc on the find bar no longer interrupts; the find bar scrolls + Enter/Shift+Enter navigation.
- **Adaptive thinking on by default**; faster Vibe Mode; **responsive** composer / find bar / chips and truncated tab titles.
- **"Always allow"** resolves the exact card; permission re-push no longer wipes in-progress input; the session dashboard no longer covers the composer; clipboard paste runs off-EDT (no IDE freeze on a hung Wayland clipboard).
- Latent fixes: double-`claude`-spawn guard + mid-launch orphan prevention, `dispose()` generation bump, a malformed `can_use_tool` can't hang the turn, and a per-project tool window (no cross-project shared state).

---

## v4.0.3 — 2026-06-10

**Fix: clipboard paste in the chat composer on native-Wayland Linux (the real fix for 4.0.2's symptom).**

On IntelliJ running the native Wayland toolkit, the embedded browser's clipboard is isolated from the system clipboard, so `Ctrl+V` in the composer only pasted things copied *inside* the chat — never text or images from other apps. 4.0.2 added the right host-side reader (`wl-paste`) but it was never reached, because the real problem is *where the paste is triggered*, not how it's read. The composer now routes `Ctrl+V` through the host whenever the Wayland toolkit is active (the same path the **Attach → Image** button already used), so pasting text and images from any app works — including pasting back what a Copy button put on the clipboard. X11/XWayland, Windows, and macOS are unchanged.

---

## v4.0.2 — 2026-06-10

**Fix: text paste on native-Wayland Linux IDEs.**

On IntelliJ 2026.1+ running the native Wayland toolkit, AWT's clipboard comes up empty, so pasting **plain text** (`Ctrl+V`) into the composer did nothing — while image paste worked, because it already had a `wl-paste`/`xclip` fallback. Text paste now uses the same host-side fallback, reading a genuine `text/*` target. It's guarded so an image-only clipboard (e.g. a KDE screenshot) is never mis-pasted as raw bytes, and copied files (`text/uri-list`) / HTML markup are excluded. X11/XWayland, Windows, and macOS are unaffected.

---

## v4.0.1 — 2026-06-10

**Protocol upgrade to `claude` 2.1.170 / SDK 0.3.170.**

Keeps the plugin in lock-step with the latest Claude Code binary. The drift detector (`./gradlew checkDrift`) flagged four new protocol kinds in 2.1.170; all are reconciled and the protocol surface is verified green again.

- **Model refusal fallback is now visible.** When the primary model declines a turn (stop_reason `refusal`), the binary retries it once on a fallback model. The plugin now recognises this new `system/model_refusal_fallback` message and shows a transcript notice — e.g. *"The model declined to respond (cyber) → retried on claude-sonnet-4-6."* — instead of silently dropping it, so you always know when a retry happened and which model produced the answer.
- **Protocol surface re-baselined** to `claude` 2.1.170 / SDK 0.3.170, with the new `get_usage` / `register_repo_root` / `reload_skills` host→binary control requests triaged into the known surface.

No UI or behavioural changes beyond the above; backward-compatible with older binaries (the new fields are all optional).

---

## v4.0.0 — 2026-06-04

**Chat UI rebuilt on JCEF (embedded Chromium).**

The entire chat surface is now an embedded Chromium web view (JCEF) instead of Swing — an inlined web app (no CDN, no external resources), themeable to your IDE. Diffs stay native via the IDE's `DiffManager`; everything else got a new web front end. The old Swing chat UI (and its tests) was removed.

- **Modern streaming transcript.** Token-by-token rendering of sanitized model markdown (tables, lists), collapsible tool cards that show live elapsed time, fenced code blocks with a language label and a Copy button, and a Ctrl/Cmd+F find bar. Links never navigate the view; external `https` links open in your system browser.
- **Web composer.** Input with model · mode · effort · thinking · provider controls, a queued-prompt strip, a predicted-next-prompt ghost suggestion, a `/` command palette, and attachment chips — including image **drag-and-drop** and **paste** straight into the composer, plus a file picker.
- **Native permission / question / elicitation cards.** Permission prompts, `AskUserQuestion`, and MCP **elicitation** (URL flow gated to http/https, or a form built from the request schema) render as inline cards — no modal dialogs.
- **Session dashboard.** A toggle flips the transcript to a dashboard: context breakdown by category, usage & cost (in / out / cache, USD when the binary reports it), account (email / org / plan / provider), the active model, in-flight subagents (with Stop), and MCP server health (status + reconnect / enable-disable per server).
- **Hardened web view.** Served from an in-process, network-less origin with a full set of real security headers and a strict, **hash-pinned** CSP (every script and the stylesheet allowed only by `sha256`, no `unsafe-inline`/`unsafe-eval`, `connect-src 'none'`), so untrusted rendered content can never fetch, exfiltrate, or execute injected script.
- **Requires JetBrains 2025.2+ (build 252+)** with JCEF (bundled with the IDE's JBR).

**Post-rewrite UI/UX hardening (4.0.0).** A full QA pass over the new JCEF surface closed a stack of bugs and re-ported the Swing features that mattered — all in the frontend, **the Kotlin backend was not touched**:

- **Subagents nest properly.** A Task/Agent card now contains its subagents' tool activity; each nested card collapses/expands on its own (was a descendant-selector bug forcing them all open).
- **Native rewind as the default rollback.** "Restore" on an edit asks Claude Code to `rewind_files` to that turn (client-tagged message uuid + `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`); falls back to the IDE-side per-file revert behind a confirmation (with a remembered "don't ask again"). Per-edit "View diff" + the Diff History rollback tab return.
- **Clipboard paste on Wayland.** Ctrl+V and "Paste image" are read host-side (text via AWT, image via `wl-paste`/`xclip` resolved across common paths), so image paste works where JCEF's web clipboard comes up empty; text pastes without duplicating.
- **Tool-card states** fade sky-blue↔amber while active, green on success, **red on error** (`is_error`). Inline edit diffs render colourised (added/removed/hunk).
- **Composer** is a flat single-row control bar (📎 · provider · model · mode · effort · thinking | 🌈 · history · follow · send) with the previous icon set; a session-usage line (running/idle + context + tokens) sits above it.
- **Restored/added:** Ctrl+O reasoning toggle (folds collapsed by default, with a hint), auto-follow scroll, the 🌈 Vibe Mode gag (Nyan Cat + rainbow), diffs open without stealing keyboard focus, request cards cap at half height with the body scrollable and actions always visible, `/login` runs in the IDE terminal (browser auto-capture) and shows in the palette, "Explain with Claude" carries the Claude icon, and a **Cancel** button on question cards.
- **Fixes:** the build didn't compile (`object a ChatTheme` + a nested-comment KDoc); session cost counters and the JetBrains MCP server now read the binary's `mcpServers` (camelCase) reply; ⚙ menu reuses the formatted dashboard instead of plain-text dialogs.

**Feature parity + web-only differentiators (4.0.0).** A second pass closed the remaining Swing gap and added what only the web view enables — still all frontend (backend wiring only reuses what already existed):

- **Hunk-by-hunk partial diff acceptance.** Reviewable Edit/Write/MultiEdit permission cards show a checkbox per changed region; accepting a subset narrows the input (`HunkSelection.encodeInput`) so the binary writes only the chosen hunks.
- **`jb://` jump-to-code links.** `@file` mentions render as clickable links that open the file at the line in the editor — DOMPurify-allowed and gated to the project root (`DiffPresenter.isWithinRoot`).
- **Rich attach menu (📎).** A search box + Files / Directory / Image + current selection/file + a filterable **Recent files** list (icon + name), AI-Assistant-style — recents from `FilePickerHelper.recentFiles`.
- **Syntax highlighting in the IDE's colours.** highlight.js token classes map to the live editor scheme (`DefaultLanguageHighlighterColors`), so code blocks match the IDE in any theme.
- **Inline images** (`data:` URIs, kept in-bounds) and a **responsive** layout for narrow tool windows.
- **Deliberately NOT added:** Mermaid / KaTeX — too much external bloat and they'd force relaxing the strict hash-pinned CSP. Kept the plugin lean (~1.6 MB) and the CSP intact.

**Expert-consensus review hardening (4.0.0).** A multi-reviewer pass over the parity changes confirmed and closed a handful of real defects — still frontend + thin UI wiring, the protocol backend untouched:

- **Partial accept never writes from a stale snapshot.** Accepting a subset of hunks now re-reads the file from disk first; if it changed since the card appeared, the plugin does a normal full accept instead of reconstructing from the cached snapshot (which could silently no-op or overwrite an external edit).
- **No more `hunkCache` growth.** Cached hunk contexts are pruned to the still-pending permissions on each push and cleared on dispose, so permissions cleared on stop/interrupt can't leak.
- **Big files stay responsive.** Files over 1 MB skip the EDT-side hunk read/diff (full accept still works) so a large file can't freeze the UI when its permission card renders.
- **`sms:` links work again** — restored in the DOMPurify allowlist alongside `data:image/` inline images and the internal `jb:` scheme (`data:text/html` stays blocked).
- **Zero-deprecation build** — the rewind-fallback dialog moved off the deprecated `Messages.showYesNoDialog(…DoNotAskOption)` overload to `MessageDialogBuilder.yesNo`. Tests green and `verifyPlugin` Compatible across IC-252 → IU-262 EAP.

**Still deferred (small, low-value now):** selecting text from an *open diff* to attach, and an "expand/collapse all" button.

---

## v3.3.0 — 2026-06-04

**The whole binary→host protocol surface, mapped into the UI — plus native MCP elicitation.**

This release closes the loop on the native protocol: **every event the `claude` binary sends the host is now parsed *and* used** — answered when it's a request, surfaced in the chat when it carries information. The two control requests that used to fail with an error are handled correctly, and a batch of events that were parsed-but-invisible are now on screen. Under the hood it's all delegated to small single-responsibility collaborators (the chat session stays a thin orchestrator), and a new `./gradlew checkDrift` keeps the native models in step as the binary and SDK keep moving.

- **MCP elicitation, natively.** When an MCP server needs your input, you now get an **inline card** (never a blocking dialog): a URL flow shows an **Open link** + Accept/Cancel; a form renders a labeled field per schema property and sends back what you type on Accept. URL links are restricted to `http`/`https` — an untrusted server can't get a `file:`/`javascript:` link opened.
- **Predicted next prompt.** A `💡` chip above tdhe composer offers the binary's predicted follow-up; click to drop it into the input (you still review and send it yourself).
- **See the model think.** The status line shows a live reasoning-token estimate mid-turn.
- **Hooks, visible.** Each hook the binary runs appears as a single transcript row that updates from "running…" to ✓/✗ — no more silent hooks.
- **Memory recall, surfaced.** A collapsible "Recalled N memories" row shows exactly what context influenced a turn.
- **Smaller touches.** Tool-use summaries render as quiet notes, and file uploads now confirm success, not just failures. `request_user_dialog` requests are answered correctly instead of erroring.
- **Drift detection.** A new `./gradlew checkDrift` keeps the native protocol models in sync as the `claude` binary and its SDK evolve.

---

## v3.2.1 — 2026-06-04

**Pick your model provider — Anthropic or DeepSeek — without ever leaking your Anthropic credentials.**

- **Provider selector.** A new `Provider:` option (in Settings and as a composer chip, with each provider's brand logo) lets you point the `claude` binary at **Anthropic** (your normal subscription/login) or **DeepSeek** (its Anthropic-compatible endpoint). Switching restarts the session.
- **DeepSeek needs its own key — and that's enforced.** A non-Anthropic provider requires its **own issued key**, kept **isolated per provider in the IDE password safe** (never in a project file). Pick DeepSeek with no key and the plugin asks you to configure it first instead of switching.
- **Your Anthropic credentials are never used for another provider.** The endpoint and key are set together as a pair, the binary won't even load your Anthropic OAuth when a provider key is present, and the form refuses an Anthropic key (`sk-ant-…`) in the DeepSeek slot. `/login` only applies to Anthropic.

This release also fixes a pesky reasoning-regression: new "Thought process" blocks now respect the Ctrl+O toggle instead of always showing expanded — toggle reasoning off once and it stays off for every subsequent turn.

It's also a hands-on way to see what these "Anthropic-compatible" Chinese endpoints actually do with Claude Code's tool calls.

---

## v3.2.0 — 2026-06-04

**Composer polish, smarter links, real rollback — and a Vibe Coder Mode nobody asked for.**

- **A two-row options bar.** Model · mode · effort · thinking pills on top (centred, each with its own icon and a hover glow), the toggles + attach + a thin neon Play/Stop on the bottom. The prompt lights a coral focus ring while you type and uses your editor font.
- **Follow toggle.** A button keeps the streaming answer pinned to the bottom even if you scroll up; turn it off to read history mid-stream (it still follows while you're at the bottom). Ctrl+O collapsing the reasoning no longer jumps the scroll.
- **Attachments that actually work.** A file you attach is sent to the binary as a `@cwd-relative` mention it expands, and shows in your message as a clickable link. More file references in answers become clickable too — even without a line number (inside backticks), while staying conservative in prose.
- **Rollback, finished.** Reverting a Write that *created* a file now deletes it (not a 0-byte husk); reverting an edit restores the prior contents; every revert shows a confirmation. The in-card Diff/Revert buttons are now easy to hit.
- **Settings that fit your screen.** The settings page no longer stretches edge-to-edge on a wide monitor.
- **Sign in without leaving the chat.** `/login` no longer opens a terminal tab (which stopped working once JetBrains made the reworked terminal the default). It now opens your browser to approve access and asks you to paste the code right in the IDE — then signs you in and reconnects automatically.
- **🌈 Vibe Coder Mode.** An opt-in toggle that turns the whole chat neon-rainbow — send glyph, pills, boxes, icons, the focus ring — and swaps the Claude avatar for a Nyan Cat. Purely for the lulz; off by default.

Also in this release (the composer overhaul):

- **Paste images (Ctrl/Cmd+V).** Paste a screenshot straight into the composer — fixed on Linux (Wayland/X11) where the clipboard hands over a raw `image/…` stream; wired through the IDE action system so it follows your keymap on every OS.
- **A proper attachment menu.** Add the current file / selection / clipboard image, files and a directory from a native picker, or pick from your **open** and **recently-opened** files; chips show the real file-type icon and open the file when clicked.
- **Its own visual identity.** Custom icons on every tool call (bash, read, edit, search, web, task…), the attach button and chips, with the Claude coral as the accent — everything else still follows your IDE theme.
- **Diff History tab.** Lists every Edit/Write Claude made this session with a `+a/-b` summary, **View diff** and **Revert**, plus **Roll back all changes**.

`verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.1 — 2026-06-03

**Performance pass — the plugin was already faster than the CLI; now it flies.**

A focused optimization patch. Same features and behaviour as 3.0.0, materially lower latency and CPU, especially during streaming and on large sessions.

- **Streaming feels instant** — assistant/thinking deltas are coalesced and applied to the UI in a single hop per batch instead of one per token, cutting EDT churn dramatically.
- **Transcript renders only what changed** — a growing reply re-lays-out its own row, not the whole conversation; markdown is memoised and syntax highlighters are cached/reused.
- **Big sessions load fast** — restore reconstructs the recent tail (the binary keeps full context via `--resume`), and tool outputs anchor in O(1) instead of scanning the transcript.
- **Lighter under load** — one shared animation timer for all tool boxes (not one each), one quota poll per session (not per tab), and an O(n) stdout line splitter.

Includes a post-review hardening pass: a cap on the stdout buffer (no unbounded growth on a malformed stream), more robust streaming auto-scroll, orphan tool-result rows dropped on restore, the usage meter no longer blanks between turns, a synchronous delta drain on teardown, and markdown that re-renders on a theme switch. Plus two field-reported fixes: toggling thinking/model no longer closes the session (a restart race), and the edit diff now shows in **every** permission mode (acceptEdits / bypass / auto / dont-ask), not only when a permission card appears.

**Log in from the IDE.** `/login` used to dead-end with *"not available on this environment"* because the chat session has no interactive terminal. Now the plugin opens a real IDE terminal and runs `claude auth login` for you (launched with the binary's full path, so it works even when the IDE didn't inherit your shell `$PATH`). A detected auth failure also offers a one-click **"Log in in terminal"** prompt.

494 tests (0 failures) plus the gated UI suite; `verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.0 — 2026-06-03

**The whole Anthropic Agent SDK, nativized in JetBrains.**

3.0.0 turns the plugin from "a great chat" into a full native surface for the Claude Agent SDK protocol — every `system/*` and stream event parsed, every host→binary control request wired to a GUI control, and a redesigned, IDE-themed composer. Still no Node or TS SDK at runtime: the plugin speaks the binary's `stream-json`/control protocol directly.

**What's new**
- **Live session consumption** — a native panel (IDE progress bars + labels) above the composer shows the context window, the **authoritative cumulative token breakdown** (input / cache write / cache read / output, from the binary's `get_session_cost`) **inline**, and a quota bar with the reset countdown **and** absolute reset hour. The quota % shows when the binary reports it (no misleading 0%).
- **Tool-call state on the box** — each tool card is **sky-blue while in flight (pulsing sky↔amber)** and **green when finished**, with elapsed time while running.
- **Rich IDE attachments** — the 📎 button opens a selector menu (current file / selection / clipboard image), files/selections pin as chips, and you can **drag & drop or paste images** straight into the composer (native image content blocks). Large files are size-guarded and read off the UI thread.
- **Native "thinking" indicator** — the IDE's own animated spinner while a turn runs.
- **Subagent (Task) live strip** — one card per in-flight subagent with its running tokens / tool-uses / elapsed time and a Stop button; paused/failed states surface inline.
- **Advanced launch options** in Settings — max turns, max budget (USD), fallback model, extra `--add-dir` roots, beta flags, strict MCP config.
- **Plan mode & richer permissions** — ExitPlanMode plan cards, decision reasons, blocked-path context.
- **Session management** — rename, fork, and delete past sessions (the binary's session files stay the source of truth).
- **Native hooks** — `hook_callback` answered host-side via a pure decision engine (the real tool gate is still `can_use_tool`).
- **Diagnostics & account** — Account, Binary Version, Effective Settings, and an interactive MCP-runtime dialog (reconnect/toggle per server) in the gear menu.
- **A Diff button on every Edit/Write/MultiEdit row** plus syntax-highlighted code.

**Under the hood**
- The two former god-objects (`ClaudeSession`, `ChatPanel`) were decomposed into focused, individually-tested collaborators (token accounting, task tracking, transcript reconciliation, diff lifecycle, control client, permission cards, hooks, launcher; UI sub-panels for permissions/queue/subagents/attachments/usage). The orchestrators are now thin.
- A final hardening pass across security, architecture, concurrency, UX and protocol-fidelity: image read/encode moved off the EDT, the absolute reset-hour and a non-colour warning marker restored, subagent status/error updates wired, shared token formatting, and more.
- A native-UI + correctness pass from hands-on testing: the consumption readout rebuilt from native components, usage sourced from the binary's authoritative `apiUsage`, human-readable permission-mode labels, the model default shown as "Default · Opus 4.8", a responsive (scrollable) Settings page, and a fix so approving a plan no longer leaves the Mode chip stuck on "plan".
- **474 automated tests** (0 failures) across unit / headless / integration, plus the gated RemoteRobot UI suite (locators validated against a live IDE). Verified Compatible against IU-261 and IU-262 (RC), with zero deprecated/internal APIs.

---

## v2.2.2 — 2026-06-03

**Full automated test pyramid + release automation — same runtime as 2.2.0**

Completes the testing and maintenance foundation started in 2.2.1. End-user behaviour is unchanged; under the hood every layer of the plugin is now covered by automated tests and releases are automated end-to-end.

**Test pyramid (now complete)**
- **Headless component tests** run the IntelliJ Platform in-process to cover services and Swing wiring that pure unit tests can't reach (diff registry, session manager, settings UI, real token accounting).
- **Integration tests** drive a real `ClaudeSession` against a deterministic `fake-claude` stand-in via JSONL fixtures — init, streaming, thinking, token fold, rate-limit, tool permission, resume, interrupt, and the auto-approve cascade regression.
- **End-to-end UI tests** (RemoteRobot) cover the click-paths a user actually takes; run on demand behind `-PuiTest.enabled=true`.
- **239 tests** in the default suite (0 failures), plus the gated UI suite.

**Release automation**
- `docs/BRANCHING.md` captures the GitFlow + branch-protection conventions. (The `release.yml` and nightly `ui-tests.yml` this section originally described were never committed; the pipeline landed in 5.0.0.)

---

## v2.2.1 — 2026-06-03

**Test pyramid + maintenance workflow — same runtime as 2.2.0, professional foundation underneath**

This release is the **infrastructure update**: the plugin's behaviour is identical to 2.2.0 but it now ships with the CI, documentation, drift detection, and test scaffolding that a Marketplace-listed plugin with real users deserves.

**Test pyramid foundations**
- **202 tests** (+67 since 2.2.0): direct security-gate coverage for `DiffPresenter.isWithinRoot` (incl. symlink escape attempts), exhaustive `PermissionBroker` matrix, `ClaudeBinaryLocator` cross-platform, `McpConfigBuilder` full transport coverage, `parseAskQuestions`, and `MarkdownRenderer` edge combinations.
- New Gradle source sets `integrationTest` and `uiTest`. The `integrationTest` task drives the plugin against `bin/fake-claude`, a deterministic Python stand-in fed JSONL fixtures from `src/integrationTest/resources/fixtures/` — Layer C scenarios will land here in 2.2.x. `uiTest` is reserved for the RemoteRobot end-to-end suite.
- `kotlinx-kover` coverage report (`./gradlew koverHtmlReport`).

**CI + maintenance workflow**
- `.github/workflows/ci.yml`: runs tests, `verifyPlugin`, and `buildPlugin` on every push and PR, uploading the plugin zip as an artifact.
- `.github/workflows/sdk-drift.yml` (weekly), `binary-drift.yml` (daily), `binary-probe.yml` (weekly + manual): open issues automatically when a newer SDK or `claude` binary is released, or when the binary starts emitting an event type the plugin doesn't parse.
- `SECURITY.md` (responsible disclosure + SLA), `CONTRIBUTING.md`, `CODEOWNERS`, issue + PR templates, Dependabot for Gradle and the SDK reference.

**Documentation**
- `docs/RELEASE_PROCEDURE.md` + `docs/RELEASE_CHECKLIST.md` (end-to-end release flow), `docs/BINARY_COMPAT.md` (which `claude` binary each plugin version was tested against), `docs/FAQ.md`, `docs/TROUBLESHOOTING.md`, `docs/TELEMETRY.md` (plain answer: we collect nothing).

**Why 2.2.1 instead of folding into 2.2.0**: 2.2.0 is the Marketplace-publishable bug-fix release that unblocked publication (`findEnabledPlugin` migration). 2.2.1 cleanly separates the runtime fixes from the maintenance/test infrastructure so the changelog tells the truth.

---

## v2.2.0 — 2026-05-28

**Marketplace-publishable, live model picker, links inside backticks, aligned with `claude` 2.1.161**

Compatibility + UX iteration on top of 2.1.0. 134 tests.

**Marketplace fix**
- Migrated the bundled MCP plugin lookup from an `@ApiStatus.Internal` API to the public static `PluginManager.getPlugin(PluginId)`. This was the lone internal-API hit blocking the upload re-check; the plugin is now Marketplace-acceptable.

**Model picker**
- The Settings model combo shows the binary's actual options (`Default (recommended)` = Opus 4.8 with 1M context, `Sonnet` = Sonnet 4.6, `Haiku` = Haiku 4.5) by their human label, and **refreshes live** when the `initialize` handshake lands — so opening Settings before the session warms up no longer leaves only the historical Opus tags.
- New installs default to `default` (the binary picks the recommended tier) instead of the hard-coded `claude-opus-4-7`. Existing settings keep what you had.

**Linkify in backticks**
- `` `src/Foo.kt:42` `` references (the natural way the model writes paths in code answers) now render as clickable `jb://open` links wrapped in `<code>`, instead of inert monospaced text. Still confined to the project root.

**Protocol**
- Bumped the SDK reference to **0.3.161**, aligned with `claude` **2.1.161**. New `ModelInfo` fields (`supportsEffort`, `supportedEffortLevels`, `supportsAdaptiveThinking`, `supportsFastMode`, `supportsAutoMode`) and `AccountInfo` fields (`apiProvider`, `apiKeySource`) are now decoded; new `system/*` events the binary now emits (`task_progress`, `task_notification`, `background_task_*`, `auth_status`, `session_state_changed`) are tolerated — UI integration in a follow-up.

---

## v2.1.0 — 2026-05-27

**Review edits anytime, readable questions, partial acceptance, sessions from the binary's own files, and more**

A round of native-only UX features, backed by new tests (132 total).

**Edit review**
- **Persistent diff from the transcript** — every Edit/Write/MultiEdit keeps a "View diff" button on its tool card, so you can re-open the old↔new diff at any time, in any permission mode (even after an auto-approved edit). The pre-write file contents are snapshotted at approval time and keyed to the tool call.
- **Hunk-by-hunk acceptance** — the permission card now lists the change's hunks with checkboxes; accept only the ones you want and the binary writes exactly that subset (the file path is never altered).

**Readability**
- **AskUserQuestion options** now wrap — long labels, descriptions and the (previously unused) per-option preview are shown in full instead of being clipped to one line.
- **Better Markdown** — strikethrough, GFM task-list checkboxes, nested lists, and a fix for double-linkified URLs.

**Editor integration**
- **"Explain with Claude"** in the editor right-click menu sends the current selection (with its file path) to the active chat.
- **Jump-to-code** — `path:line` references in Claude's replies become clickable links that open the file at that line (confined to the project tree).

**Permissions**
- **"Always allow" per tool** — approve a tool once and skip its prompt for the rest of the project; reviewable writes still stay confined to the project root. Settings lists the remembered tools with a Remove button, so you can revoke any rule.

**Sessions**
- **Reads the binary's own session files** — past conversations come straight from Claude Code's transcripts (`~/.claude/projects`), so the plugin never stores a copy of your chats. **Open Previous Session…** lists the project's sessions by their real title (the one `--resume` shows) and re-attaches with `--resume`.
- **Restore on startup** — the tabs you had open are reopened automatically; if none were recorded, your most recent session is restored. Turn it off in Settings ▸ Claude Code ▸ "Restore open chats on startup".
- A background session that needs attention (pending permission, finished turn, or error) raises a notification and a tab badge — suppressed only for the chat currently on screen; the notification's **Open** button dismisses it.

**Security**
- Jump-to-code links are confined to the project root (a crafted `path:line` can't open `~/.ssh`, `/etc`, or `..`-traversed files), and explicit Markdown links are restricted to an allow-list of schemes with their href escaped.
- The plugin writes **no conversation content** to project files — only which tabs were open (in `workspace.xml`). Session-file reads stay inside `~/.claude/projects` behind a UUID-shaped id check, so a crafted session id can't traverse out.

**Fixes**
- Reasoning shows again on current models: extended thinking now uses the launch flags `--thinking adaptive --thinking-display summarized` (the old `set_max_thinking_tokens` control stopped surfacing it). Thinking is on/off — adaptive, the model decides depth.
- No more "Write-unsafe context!" crash when refreshing edited files (the VFS refresh is now asynchronous).

---

## v2.0.1 — 2026-05-27

**Compatibility update**

- Extended the supported IDE range to the current EAP: `until-build` is now `262.*`, so the plugin installs and runs on the 2026.2 EAP builds (verified Compatible against IU-262 with `verifyPlugin`).
- Replaced the internal `PluginManagerCore` API used to locate the bundled MCP Server plugin with the public `PluginManager` lookup by plugin id (`com.intellij.mcpServer`), removing the only internal-API usage and dropping the fragile path-name heuristic.

---

## v2.0.0 — 2026-05-26

**Reliability & security hardening + first unit-test suite**

This release is a stability milestone: a multi-profile review (security, clean-code, SRE) drove a round of fixes across the process lifecycle, permission handling and the protocol layer, backed by the project's first automated tests.

**Reliability**
- Fixed an EDT freeze on session start: resolving the process environment (which sources a login shell, up to a multi-second timeout) and spawning the binary now run off the UI thread; the resolved environment is cached per session. The IDE no longer hangs when opening the first chat or sending the first prompt.
- In-flight control requests (`get_context_usage`, session cost, MCP status, the initialize handshake) are now resolved when the process stops or crashes, instead of leaving dialogs stuck on "Loading…".
- Control requests get a 30s watchdog, so a hung binary no longer leaves a callback pending forever.
- Process start failures are now surfaced (notification + log) instead of leaving a half-initialized "ready" session; writes to a dead stdin are logged instead of silently dropped.

**Security**
- Auto-approved file writes (in `acceptEdits` / `bypassPermissions`) are now confined to the project root: a write whose path resolves (symlinks included) outside the project falls back to a manual Accept/Reject card.
- Trust-on-open gate: if a project's `claude-code.xml` carries a source script or a custom stdio MCP server (both execute code at launch), the plugin asks for confirmation once before running them.
- The source-script argument is now passed without shell interpolation (no injection via a crafted path), and Settings now warns that environment variables are stored in plain text and that the source script is executed on start.

**Quality**
- First unit-test suite (80 tests): protocol parsing/building, diff reconstruction, transcript hierarchy, rate-limit math, environment parsing.
- Internal cleanups: MCP config building extracted to a testable unit, thread-safe tab counter, named constants, and quieter-failure logging.

---

## v1.3.5 — 2026-05-26

**IDE tools over MCP (opt-in)**
- New section in Settings ▸ Claude Code with two independent controls:
  - **Enable JetBrains MCP server** — let Claude query the IDE (diagnostics, open files, usages, …) through JetBrains' own MCP Server plugin. Choose the transport (`sse`, `streamable-http`, `stdio`) and port. For `sse`/`streamable-http` the localhost endpoint is filled in for you; **`stdio` is assembled automatically from the running IDE** (its JBR `java` and bundled `mcpserver` libs), so there's nothing to paste and it works on Windows unchanged.
  - **Custom MCP servers** — add as many of your own MCP servers as you like, as a JSON object (`name → server config`).
- Off by default. Every IDE tool call is still gated by the in-chat permission prompt; enable only on a machine you trust.

> Requires JetBrains' **MCP Server** plugin enabled (Settings ▸ Plugins) for the JetBrains option.

---

## v1.3.1 — 2026-05-26

**Fixes & defaults**
- Fixed the Settings model dropdown showing empty when opened before the initialize handshake — it now always lists available models plus known fallbacks.
- Removed the blank entry from the Effort dropdown in Settings.
- Default model is now **Opus 4.7**; default effort is now **medium**.

---

## v1.3.0 — 2026-05-26

**Windows support**
- The `claude` binary is now detected on Windows (`claude.exe` / `claude.cmd`) across npm, scoop, volta, chocolatey and `~\.local\bin`.
- npm `.cmd` shims are driven as `node cli.js` directly, bypassing cmd.exe — which corrupted the streaming stdio pipe (stdin EOF triggered "Terminate batch job (Y/N)?") and mangled argument quoting. This fixes both the "not a valid Win32 application" (error 193) failure and the "found but no response" hang.

**Configurable paths & environment**
- Explicit overrides for the `claude` and `node` executable paths in Settings — the catch-all for custom install dirs, version managers, or a GUI IDE that doesn't inherit the user's PATH.
- Configurable environment variables (`KEY=VALUE` per line) injected into the binary's process — useful on Windows for `PATH` additions.
- **Source script**: point to a `.sh` (sourced in the login shell on Linux/macOS) or a PowerShell profile/`.ps1` (dot-sourced on Windows); its resulting environment is captured and applied to the `claude` process, so the IDE inherits the same `PATH`/setup as the user's own shell.
- The "binary not found" notification now offers a **Configure paths…** action that jumps straight to the settings page.
- The auto-detected `claude` path is persisted on first launch (and refreshed when stale), so startup is stable and the path is visible/editable.

---

## v1.2.0 — 2026-05-26

**Hierarchical, collapsible transcript**
- Every tool call is now a collapsible group: a disclosure triangle on the tool card shows or hides its output.
- Subagent (`Task`/Agent) activity nests under its Agent — the subagent's own tool calls, outputs and text are anchored and indented beneath the Agent card. Collapsing is hierarchical: collapse the Agent to fold its whole subtree, or collapse a single sub-tool to fold just its output.
- Tool outputs now anchor directly under their tool call instead of drifting to the end of the transcript. This fixes outputs of tools that require human interaction (permission cards, `AskUserQuestion`) and long-running/parallel calls, where the result previously landed at the tail.

**Build**
- `JBUI.scale` → `JBUIScale.scale` (correct API for stroke scaling in IntelliJ Platform 2025+).

**Info bar**
- Reordered: (1) Resets in countdown, (2) Reset Hour, (3) Session Usage %, (4) Brewing / live tokens / Esc to interrupt.

---

## v1.1.0 — 2026-05-26

**Quota & rate-limit fixes**
- Quota bar stays visible with reset countdown when utilization % is not reported (Max plans); % meter hides independently.
- `isWarning` / `isExhausted` no longer fire on `overageStatus = "rejected"` alone.
- Token counter now accumulates correctly across multi-message turns (tool calls, chained assistant messages).

**Session reliability**
- Failed turns with no `result` text (`error_*` subtypes) surface the `errors` list or subtype name — no more silent failures.
- `dispose()` sends EOF before killing the process (clean exit, same order as `stop()`).
- `LiveUsage` updates moved to EDT to eliminate read-modify-write race on token counters.
- `ready` and `process` marked `@Volatile` — visibility gap on session start/stop across threads fixed.
- Startup queue flushed after `system/init` — messages sent before the handshake are no longer dropped.

**Protocol**
- `errors: List<String>` field added to `ResultMessage` to capture SDK `SDKResultError.errors` payloads.

---

## v1.0.0 — 2026-05-26

First stable release.

### Features

**Streaming chat**
- Real-time token streaming with animated thinking indicator and live token counter
- Multi-tab support — open independent sessions per project
- Multi-prompt queue: type while the agent is working, messages are sent in order when the turn ends
- `/btw` side-questions sent mid-turn without interrupting the active turn
- Interrupt (Esc) to stop the current turn at any time

**Full slash-command palette**
- All commands from the `claude` binary are surfaced natively (Ctrl+K / Cmd+K)

**Model & runtime controls**
- Model selector chip (all models available in your account)
- Permission mode chip (default / acceptEdits / bypassPermissions)
- Effort chip (low / medium / high) for extended thinking
- Thinking token budget (Ctrl+O to toggle)

**Native diff review for file edits**
- When the agent requests to write or edit a file, a diff opens inline in the editor area
- Non-modal Accept / Reject card in the chat — no popups or modal dialogs
- On acceptance the binary writes the file; VFS is refreshed automatically

**Permission & question handling**
- `can_use_tool` requests surface as native inline cards (Accept / Reject)
- `AskUserQuestion` rendered as a structured question card with option buttons
- Auto-approve in `acceptEdits` / `bypassPermissions` modes

**Quota bar**
- Subscription usage % shown when the binary reports it (near the usage limit)
- Displays reset window countdown and overage status

**Settings**
- Configurable default model, permission mode, effort, thinking tokens
- Allowed / disallowed tools, setting sources, output style
- All settings accessible via Settings → Tools → Claude Code

**IDE integration**
- Tool window anchored to the right panel (same area as AI Assistant)
- Light and dark theme support with custom icons
- Works in any IntelliJ Platform IDE (IDEA, PyCharm, WebStorm, GoLand, etc.)

### Requirements

- JetBrains IDE 2024.3 – 2025.1.x (build 243–261)
- [`claude` binary](https://claude.ai/code) installed and on `PATH` or `~/.local/bin/`
- Claude subscription (claude.ai) or `ANTHROPIC_API_KEY` environment variable

