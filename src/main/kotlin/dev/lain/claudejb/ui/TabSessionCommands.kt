package dev.lain.claudejb.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EntryDTO
import dev.lain.claudejb.session.SessionHistory
import dev.lain.claudejb.session.SessionRef
import dev.lain.claudejb.session.SessionStore
import dev.lain.claudejb.session.SessionTitleReader
import dev.lain.claudejb.session.SessionTranscriptReader
import dev.lain.claudejb.settings.ClaudeSettings
import javax.swing.JList

/**
 * The conversation commands behind the tool window's gear menu — restore, rename, fork, reopen — and how a
 * past session is written in the chooser.
 *
 * Extracted from [ClaudeToolWindowFactory], which registers and wires the tool window. These are a different
 * subject: they are about the user's conversations (the binary's session files are the source of truth, so
 * every one of them re-reads a transcript off the EDT) and they need the factory for exactly one thing —
 * [openChat], which is the only place that knows how to build and wire a tab.
 */
internal class TabSessionCommands(
    private val project: Project,
    private val tabs: ChatTabsPanel,
    /** Starts a session's process, then adds a tab for it and wires it — see [ClaudeToolWindowFactory.openChat]. */
    private val openChat: (ClaudeSession) -> Unit,
) {

    private fun activeSession(): ClaudeSession? = tabs.selectedChat?.session

    /** A restorable tab: the binary session id, its resolved title and the transcript read back from the session file. */
    private data class RestoredSession(val id: String, val title: String?, val entries: List<EntryDTO>)

    /**
     * On startup, reopens the tabs that were open last time (in their stored order) by re-reading each transcript
     * from the binary's session file (the source of truth) and re-attaching via `--resume`. When no open tabs were
     * recorded (e.g. first run after install), it falls back to the most recent session for the project, so the
     * default is always "resume your last conversation" rather than an empty chat. Ids whose session file no longer
     * exists are skipped; only if there's genuinely nothing to restore does a fresh chat open. Restore can be turned
     * off in settings. The blocking session-file reads run on a pooled thread; tabs are opened back on the EDT.
     */
    fun restoreOrCreate() {
        val manager = ChatSessionManager.getInstance(project)
        if (!ClaudeSettings.getInstance(project).restoreOpenChatsOnStartup) {
            openChat(manager.create())
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ids = SessionHistory.getInstance(project).openSessions()
                .filter { SessionStore.exists(it) }
                .ifEmpty { listOfNotNull(SessionTranscriptReader.listSessions(project).firstOrNull()?.sessionId) }
            val restored = ids
                .map {
                    RestoredSession(
                        it,
                        SessionTitleReader.readTitle(it),
                        SessionTranscriptReader.readEntries(
                            it,
                            SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                            project.basePath,
                        ),
                    )
                }
            ApplicationManager.getApplication().invokeLater({
                if (restored.isEmpty()) {
                    openChat(manager.create())
                } else {
                    for (r in restored) {
                        val s = manager.create()
                        s.title = r.title ?: s.title
                        s.restore(r.id, r.entries)
                        openChat(s)
                    }
                }
            }, ModalityState.any())
        }
    }

    /**
     * Renames the active session: prompts for a new title and hands it to [ClaudeSession.renameSession], which drives
     * the binary's `/rename` (persisting a `customTitle` line) and relabels the tab via the title-changed listener.
     * No-op when there's no active chat or the input is blank/unchanged.
     */
    fun renameActiveSession() {
        val session = activeSession() ?: return
        val input = Messages.showInputDialog(
            project,
            "New session name:",
            "Rename Session",
            null,
            session.title,
            null,
        )?.trim().orEmpty()
        if (input.isEmpty() || input == session.title) return
        session.renameSession(input)
    }

    /**
     * Forks the active session into a new tab: re-reads the source transcript from the binary's session file (the
     * source of truth) and restores it into a freshly-created [ClaudeSession], then opens it. Per the E5 spec the
     * fork reuses the source `sessionId`, so [openChat]'s `start()` re-attaches via `--resume`; the binary branches
     * the conversation once the new tab sends its first message. No-op when there's no active session id yet.
     */
    fun forkActiveSession() {
        val source = activeSession() ?: return
        val sourceId = source.sessionId ?: run {
            Messages.showInfoMessage(project, "This session hasn't been initialized yet — nothing to fork.", "Claude Code")
            return
        }
        val sourceTitle = source.title
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = SessionTranscriptReader.readEntries(
                sourceId,
                SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                project.basePath,
            )
            ApplicationManager.getApplication().invokeLater({
                val manager = ChatSessionManager.getInstance(project)
                val s = manager.create()
                s.title = "$sourceTitle (fork)"
                s.restore(sourceId, entries)
                openChat(s)
            }, ModalityState.any())
        }
    }

    /**
     * Lets the user reopen a past chat: lists the binary's sessions (title + relative time, read from the session
     * files), and on pick creates a fresh session, restores its transcript + sessionId, then opens the tab. Because
     * [openChat] calls `session.start()` whose `resume` default keys off `sessionId != null`, the restored id makes
     * the binary re-attach via `--resume` automatically. The blocking session-file reads run on a pooled thread;
     * the popup and tab opening happen on the EDT.
     */
    fun openPreviousSession() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val refs = SessionTranscriptReader.listSessions(project)
            ApplicationManager.getApplication().invokeLater({
                if (refs.isEmpty()) {
                    Messages.showInfoMessage(project, "No previous sessions have been saved yet.", "Claude Code")
                    return@invokeLater
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(refs)
                    .setTitle("Open Previous Session")
                    .setRenderer(SessionRefRenderer())
                    .setItemChosenCallback { ref ->
                        // Re-read the transcript off-EDT, then build/open the tab on the EDT.
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val entries = SessionTranscriptReader.readEntries(
                                ref.sessionId,
                                SessionTranscriptReader.DEFAULT_RESTORE_CAP,
                                project.basePath,
                            )
                            ApplicationManager.getApplication().invokeLater({
                                val manager = ChatSessionManager.getInstance(project)
                                val s = manager.create()
                                s.title = ref.title
                                s.restore(ref.sessionId, entries)
                                openChat(s)
                            }, ModalityState.any())
                        }
                    }
                    .setRequestFocus(true)
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }, ModalityState.any())
        }
    }

    /**
     * Two-line renderer for a past session: the title with relative time on the first line, and best-effort metadata
     * (git branch · absolute creation date · the first prompt, truncated) on a dimmed second line. Built as HTML so a
     * single [JList] cell can show both lines; missing metadata fields are simply omitted.
     */
    private class SessionRefRenderer : SimpleListCellRenderer<SessionRef>() {
        override fun customize(
            list: JList<out SessionRef>,
            value: SessionRef?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val parts = buildList {
                value.gitBranch?.let { add(escapeHtml(it)) }
                value.createdAt?.let { add(escapeHtml(formatCreatedAt(it))) }
                value.firstPrompt?.let { add(escapeHtml(truncate(it.replace('\n', ' '), PROMPT_PREVIEW_MAX))) }
            }
            val sub = if (parts.isEmpty()) {
                ""
            } else {
                "<br><font color='#888888'>${parts.joinToString("  ·  ")}</font>"
            }
            text = "<html>${escapeHtml(value.title)}  —  ${relativeTime(value.lastModified)}$sub</html>"
        }
    }

    companion object {
        /** Max characters of the session's first prompt shown as the subtitle in the "Open Previous Session" list. */
        private const val PROMPT_PREVIEW_MAX = 60

        // Units for [relativeTime]'s "5m ago" / "2h ago" / "3d ago" bucketing.
        private const val MILLIS_PER_SECOND = 1000
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_DAY = 86_400

        /** Renders the binary's ISO-8601 createdAt as a short local date, falling back to the raw string. */
        private fun formatCreatedAt(iso: String): String =
            runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
                .getOrDefault(iso)

        /** Shared with [ClaudeToolWindowFactory.tabTitle]: one ellipsis rule for both lists of conversations. */
        fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

        /** Minimal HTML escaping so user-supplied titles/prompts can't break the cell's HTML rendering. */
        private fun escapeHtml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /**
         * Coarse, human-friendly elapsed-time label for an epoch-millis timestamp.
         *
         * Shared with [GitContextActions.commitRow] — same reason as [truncate]: a past session and a past
         * commit are both "how long ago was this", and two bucketings that drift apart is how the same instant
         * ends up written two ways in one menu. [now] is a parameter so the rule can be tested without a clock.
         */
        fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
            val secs = (now - timestamp).coerceAtLeast(0) / MILLIS_PER_SECOND
            return when {
                secs < SECONDS_PER_MINUTE -> "just now"
                secs < SECONDS_PER_HOUR -> "${secs / SECONDS_PER_MINUTE}m ago"
                secs < SECONDS_PER_DAY -> "${secs / SECONDS_PER_HOUR}h ago"
                else -> "${secs / SECONDS_PER_DAY}d ago"
            }
        }
    }
}
