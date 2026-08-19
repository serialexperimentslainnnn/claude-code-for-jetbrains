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

internal class TabSessionCommands(
    private val project: Project,
    private val tabs: ChatTabsPanel,
    private val openTab: (ClaudeSession, Boolean) -> Unit,
) {

    private fun openChat(session: ClaudeSession) = openTab(session, true)

    fun newChat() = openChat(ChatSessionManager.getInstance(project).create())

    private fun activeSession(): ClaudeSession? = tabs.selectedChat?.session

    private data class RestoredSession(val id: String, val title: String?, val entries: List<EntryDTO>)

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
        private const val PROMPT_PREVIEW_MAX = 60

        private const val MILLIS_PER_SECOND = 1000
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_DAY = 86_400

        private fun formatCreatedAt(iso: String): String =
            runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
                .getOrDefault(iso)

        fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

        private fun escapeHtml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
