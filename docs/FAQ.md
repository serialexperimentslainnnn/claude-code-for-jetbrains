# FAQ

Short answers to the questions we get most. For deeper diagnostics see
[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md).

## How do I install the plugin?

Settings → Plugins → Marketplace → search **Claude Code Native** →
Install → restart the IDE. Or install from disk using the zip from
[GitHub Releases](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/releases).

After install, a "Claude Code" tool window appears on the right.

## Which Claude account does the plugin use?

**The one you sign into from the chat's sign-in card** — and the plugin holds
that credential itself, which is the part worth knowing.

A chat tab that has no credential shows a sign-in card. It runs `claude auth
login` for you, the binary opens your browser and captures the callback, and
then the plugin **harvests the resulting credential into the IDE's PasswordSafe
and deletes `~/.claude/.credentials.json`**. From then on it is handed to the
binary in the environment for each session. An `ANTHROPIC_API_KEY` typed into
the card (or into Settings ▸ Claude Code ▸ Provider) is stored the same way.

Two consequences people notice:

- **A login you made in your own terminal is also harvested**, and that file is
  deleted. Deliberate: the credential ends up in your keychain instead of a
  plaintext file. Your terminal `claude` will ask you to log in again.
- **Signing out from the dashboard clears the plugin's safe and nothing else.**
  It does not run `claude auth logout`, because that would kill your terminal's
  login too.

## How do I change the model?

Click the **model chip** at the bottom of the chat composer. Selecting a
different model restarts the current session under `--resume`, so the transcript
is preserved. The default lives in **Settings ▸ Claude Code**.

## Which models are in the list?

**Whatever your binary reports** — the list is read from the `initialize` reply,
not hardcoded, so a new model appears without a plugin update. Entries are
labelled from the binary's own `description`, because its short display name
omits the version and made two generations of Opus indistinguishable.

The floating `default` alias is filtered out on purpose: the binary lists it
*and* the concrete model it resolves to, which is the same model twice, once
without a version. The plugin pins the concrete Opus 1M-context model instead,
falling back to the binary's recommendation and then to the first model listed
if that one is not on offer.

## How do I see what a session costs, and how much of my plan is left?

Open the **session dashboard** (⚙ in the chat). It shows the context breakdown,
the session cost, and the **plan limits**: every rate-limit window with its reset
time, per-model weekly buckets where your plan reports them, and the extra-credit
balance. The composer carries the same figures as its own row of labelled bars
under the status line, colour-coded, and announces a window once per threshold
crossed.

That comes from the binary's `get_usage` control request — the same numbers the
Claude apps show, so there is no longer a reason to leave the IDE for them.

## I get "Connection refused" or "Claude binary not found"

The plugin looks for `claude` on `PATH` and then at `~/.local/bin/claude`
(Linux/macOS). If neither is found, an actionable notification appears.

Fix:

1. Verify the binary exists: `which claude` (or `where claude` on Windows).
2. If it is in a non-standard location, set it in **Settings ▸ Claude Code ▸
   claude executable path**.
3. On Windows, prefer the native `claude.exe`; the extensionless npm shim cannot
   be spawned directly.

The chat also offers to install it for you when it is missing, using the
official per-OS route. The detection is re-run every few seconds while no
session is up, so installing the binary in a terminal takes effect without
closing the tab.

See [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) for more.

## Why does the plugin need my IDE's password store?

Because that is where its settings and credentials live. Since 5.5.0 the whole
settings document is in the IDE's PasswordSafe rather than in
`.idea/claude-code.xml` — they were per-project plaintext in a file people
commit, and they include an env block, which is where an API key or a
credentialed proxy URL ends up. The scope worth knowing: **since 5.7.0 the
settings are one document per IDE installation, per project** — two IDEs on the
same machine keep separate settings, and so do two projects in the same IDE.
(Between 5.5.0 and 5.7.0 there was a single global document; it is kept as the
seed a project without settings of its own starts from.)

If the safe cannot be read (a locked KWallet, say), the plugin treats that as a
failure and refuses to save over it — a failed read is not an empty
configuration.

## I configured this project in another IDE — do I have to do it again?

No. **Settings ▸ Claude Code ▸ Transfer ▸ Migrate from another IDE…** lists the
JetBrains IDEs that have run on this machine, and for the one you pick, the
projects it actually has Claude Code settings for. Choose whether you want the
general settings, the Sensitive Guard's, or its alert history.

Every JetBrains IDE shares one keychain, so nothing leaves it: the copy is from
one encrypted entry to another. What separates them is the **scope** — an entry
is keyed by the IDE's configuration directory *and* the project — which is why
this IDE has no settings for a project until something writes them. It is also
why JetBrains' own *Import Settings* does not bring these across: that copies
configuration files, and none of this is in one.

For another machine, or a colleague, use **Export settings…** and **Import
settings…** instead. An exported file deliberately **never carries your
environment variables**: that is where an API key or a credentialed proxy URL
ends up, and a file leaves the machine. Provider keys and Git host tokens are
not in it either — they have never been part of this document. A migration
between IDEs *does* carry the environment, because it never leaves the keychain.

## How do I disable restoring open chats on startup?

**Settings ▸ Claude Code** → uncheck **Restore open chats on startup**. The
plugin will then start with a single empty tab instead of reopening your
previous sessions via `--resume`.

## How do I clean up leftover diff tabs?

Close them the way you close any editor tab — the standard close shortcut, or
right-click ▸ **Close All Tabs**. Diffs opened by the plugin are real editor
tabs, not modal windows, so they stay until you close them; the plugin closes
the ones it opened when the session that opened them goes away.

## How do I undo something Claude changed?

Three different questions, three different answers, and picking the wrong one is
why this entry exists:

- **One edit** — press **Restore** on that tool card in the transcript. It asks
  Claude Code to rewind the files of that turn, and falls back to reverting the
  file from the snapshot the plugin captured before the write.
- **Everything a long run touched** — ⚙ ▸ **Review This Session's Changes…**
  opens the whole session as one diff against the state it started from. It is a
  review, not an undo: you read it, then decide.
- **A commit** — the **Git** button in the title bar. See
  [`../README.md`](../README.md).

There is no "roll back everything" button, deliberately: between Claude's edits
are your own, and reverting the lot would take yours with it. With a repository,
the IDE's Local Changes does that job and gives you a way back.

## Which IDE versions does it run in?

**Build `253.29346.138` — IDEA 2025.3.1 — and newer.** The floor is a build, not a
version line: the chat UI is the IDE's embedded browser, and the platform serves
its classes through a module id the plugin must declare a dependency on. That id
is missing from 2025.1, from 2025.2 and from the first 2025.3 (`253.28294.334`)
alike, and it arrives in 2025.3.1 — so declaring it, which is what makes the
plugin work on 2026.2 at all, costs all three. On those, stay on **5.1.1**. There
is no browser-less mode to fall back to. Help ▸ About prints the build number you
have.

## Why does each agent get its own tab now?

Because one transcript could not hold them. A session running agents under
agents plus background tasks filled the chat with consecutive "Thought process"
rows belonging to different agents, interleaved, with no way to follow any one of
them. Each agent now has its own tab and its own transcript, nested to match who
spawned whom, and the main transcript links to an agent instead of inlining it.

Only agents **this plugin** started are shown. A session you also resumed from a
terminal leaves its own agents in the same directory, and those never appear.

## Does the plugin send my code or prompts anywhere?

No. The plugin collects nothing about you and sends nothing to us. Your
conversations go from the `claude` binary to Anthropic over the same
channel `claude` already uses in your terminal. The one other call it
makes is optional and goes to your own forge: with a GitHub or GitLab
token it asks that server about your branch, to show you your pull
requests and CI status.

## How do I send feedback?

- Bugs and features: open an issue using the templates under
  [`.github/ISSUE_TEMPLATE/`](../.github/ISSUE_TEMPLATE).
- Security: see [`../SECURITY.md`](../SECURITY.md).
- General feedback: leave a Marketplace review.
