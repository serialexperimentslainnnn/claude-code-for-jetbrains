package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefTabsData
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class ChatTabsPanel : JBPanel<ChatTabsPanel>(BorderLayout()), Disposable {

    internal class ChatTab(
        val id: String,
        val component: JComponent,
        var title: String,
        var tooltip: String,
        val disposer: Disposable?,
    ) {
        var attention: Boolean = false

        var lastNotified: Long = 0L

        val session: ClaudeSession? get() = (component as? JcefChatPanel)?.session
    }

    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val tabs = ArrayList<ChatTab>()
    private var selectedTab: ChatTab? = null
    private var seq = 0

    private var onClosed: (ChatTab) -> Unit = {}
    private var onSelected: (ChatTab?) -> Unit = {}

    private var disposed = false

    val selected: ChatTab? get() = selectedTab

    val selectedChat: JcefChatPanel? get() = selectedTab?.component as? JcefChatPanel

    var commands: TabSessionCommands? = null

    init {
        add(content, BorderLayout.CENTER)
    }

    fun onEvents(selected: (ChatTab?) -> Unit, closed: (ChatTab) -> Unit) {
        onSelected = selected
        onClosed = closed
    }

    fun add(component: JComponent, title: String, tooltip: String, disposer: Disposable?): ChatTab {
        val tab = ChatTab("chat-${seq++}", component, title, tooltip, disposer)
        tabs += tab
        content.add(component, tab.id)
        pushChats()
        return tab
    }

    fun select(tab: ChatTab) {
        if (tab !in tabs) return
        selectedTab = tab
        tab.attention = false
        pushChats()
        cards.show(content, tab.id)
        (tab.component as? JcefChatPanel)?.let { panel ->
            runCatching { panel.transcript.showTranscript(null) }
                .onFailure { LOG.warn("Claude Code tabs: showing '${tab.title}' failed to reset its transcript", it) }
            runCatching { panel.focusInput() }
                .onFailure { LOG.warn("Claude Code tabs: showing '${tab.title}' failed to take focus", it) }
        }
        onSelected(tab)
    }

    fun panelOf(id: String): JcefChatPanel? =
        tabs.firstOrNull { it.id == id }?.component as? JcefChatPanel

    fun tabFor(session: ClaudeSession): ChatTab? =
        tabs.firstOrNull { (it.component as? JcefChatPanel)?.session === session }

    fun selectById(id: String) {
        val tab = tabs.firstOrNull { it.id == id }
        if (tab == null) {
            LOG.warn(unknownTab("selectChat", id))
            return
        }
        select(tab)
    }

    fun closeById(id: String) {
        val tab = tabs.firstOrNull { it.id == id }
        if (tab == null) {
            LOG.warn(unknownTab("closeChat", id))
            return
        }
        close(tab)
    }

    private fun unknownTab(gesture: String, id: String): String =
        "Claude Code tabs: $gesture named '$id', which is not an open tab — the page is drawing a list this " +
            "strip no longer has. Open now: ${tabs.map { it.id to it.title }}"

    fun close(tab: ChatTab) {
        if (!tabs.remove(tab)) return
        if (selectedTab === tab) {
            selectedTab = null
            tabs.firstOrNull()?.let { select(it) } ?: pushChats()
        } else {
            pushChats()
        }
        onClosed(tab)
        content.remove(tab.component)
        tab.disposer?.let { Disposer.dispose(it) }
        content.revalidate()
        content.repaint()
        replaceLastChat()
    }

    private fun replaceLastChat() {
        if (tabs.isNotEmpty()) return
        val open = commands
        if (open == null) {
            LOG.warn(
                "The last chat was closed with no tab commands wired, so no replacement was opened: the tool " +
                    "window is left with no chats and no control to create one.",
            )
            return
        }
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed && tabs.isEmpty()) open.newChat() },
            ModalityState.defaultModalityState(),
        )
    }

    fun all(): List<ChatTab> = tabs.toList()

    fun relabel(tab: ChatTab, title: String, tooltip: String) {
        tab.title = title
        tab.tooltip = tooltip
        pushChats()
    }

    fun badge(tab: ChatTab, attention: Boolean) {
        if (tab === selectedTab) return
        tab.attention = attention
        pushChats()
    }

    private fun pushChats() {
        val list = chatList()
        tabs.forEach { (it.component as? JcefChatPanel)?.agentTabs?.setChats(list) }
    }

    fun chatList(): List<JcefTabsData.Chat> = tabs.map {
        JcefTabsData.Chat(it.id, it.title, it === selectedTab, it.attention)
    }

    fun workloads(): List<JcefSessionData.Workload> = tabs.mapNotNull { tab ->
        (tab.component as? JcefChatPanel)?.let { panel ->
            JcefSessionData.Workload(tab.id, tab.title, tab === selectedTab, panel.session)
        }
    }

    override fun dispose() {
        disposed = true
        tabs.forEach { tab -> tab.disposer?.let { Disposer.dispose(it) } }
    }

    private companion object {
        private val LOG = logger<ChatTabsPanel>()
    }
}
