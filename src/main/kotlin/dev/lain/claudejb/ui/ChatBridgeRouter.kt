package dev.lain.claudejb.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import dev.lain.claudejb.context.ImageAttachments
import dev.lain.claudejb.context.ProjectTree
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.permission.ToolInputScanner
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.WorkloadWindow
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardWhitelists
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecuritySuspensions
import dev.lain.claudejb.settings.sensitivePolicy
import dev.lain.claudejb.ui.jcef.JcefBridge
import dev.lain.claudejb.ui.jcef.JcefSettingsMenu
import dev.lain.claudejb.ui.jcef.JcefTranscriptPayload
import dev.lain.claudejb.ui.jcef.JcefTreeData
import dev.lain.claudejb.ui.jcef.JcefVulnData
import dev.lain.claudejb.vuln.VulnService
import kotlinx.serialization.json.JsonObject
import java.awt.datatransfer.StringSelection

internal class ChatBridgeRouter(private val panel: JcefChatPanel) {

    private val session: ClaudeSession get() = panel.session
    private val tray: AttachmentTray get() = panel.tray
    private val feed: SessionFeed get() = panel.feed

    fun dispatch(json: String) {
        when (val m = JcefBridge.parse(json)) {
            is JcefBridge.Msg.Prompting -> onPrompting(m)
            is JcefBridge.Msg.Settings -> onSettings(m)
            is JcefBridge.Msg.RequestCard -> onRequestCard(m)
            is JcefBridge.Msg.Diffs -> onDiffs(m)
            is JcefBridge.Msg.Attachments -> onAttachments(m)
            is JcefBridge.Msg.SessionControl -> onSessionControl(m)
            is JcefBridge.Msg.Lifecycle -> onLifecycle(m)
        }
    }

    private fun onPrompting(m: JcefBridge.Msg.Prompting) = when (m) {
        is JcefBridge.Msg.Send ->
            if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.send(m.text) else dispatchSend(m.text)

        is JcefBridge.Msg.Interrupt ->
            if (m.scope == JcefBridge.SCOPE_GIT) panel.gitChat.interrupt() else session.interrupt()

        JcefBridge.Msg.CycleMode -> session.settings.cyclePermissionMode()

        is JcefBridge.Msg.RemoveQueued -> session.removeQueued(m.index)

        is JcefBridge.Msg.Copy -> CopyPasteManager.getInstance().setContents(StringSelection(m.text))
    }

    private fun onSettings(m: JcefBridge.Msg.Settings) = when (m) {
        is JcefBridge.Msg.ChangeModel -> session.settings.changeModel(m.value)

        is JcefBridge.Msg.ChangeMode -> session.settings.changePermissionMode(m.wire)

        is JcefBridge.Msg.ChangeEffort -> session.settings.changeEffort(m.value)

        is JcefBridge.Msg.ChangeThinking ->
            session.settings.changeThinkingTokens(if (m.on) ClaudeSession.THINKING_ON else null)

        is JcefBridge.Msg.ChangeVibe -> {
            ChatTheme.setVibeMode(m.on)
            JcefChatPanel.broadcastTheme()
        }

        is JcefBridge.Msg.ChangeProvider -> session.settings.changeProvider(Provider.fromId(m.id))

        is JcefBridge.Msg.SettingsToggle -> onSettingsToggle(m)

        is JcefBridge.Msg.Guard -> onGuard(m)

        JcefBridge.Msg.SettingsRefresh ->
            ClaudeSettings.getInstance(panel.project).reload { JcefChatPanel.pushSettingsMenuToAll() }

        JcefBridge.Msg.OpenSettings ->
            ShowSettingsUtil.getInstance().showSettingsDialog(panel.project, ClaudeSettingsConfigurable::class.java)
    }

    private fun onGuard(m: JcefBridge.Msg.Guard) = when (m) {
        is JcefBridge.Msg.GuardSuspend -> onGuardSuspend(m)
        is JcefBridge.Msg.GuardMaster -> onGuardMaster(m)
        is JcefBridge.Msg.GuardWhitelist -> onGuardWhitelist(m)
        is JcefBridge.Msg.GuardRevokeApproval -> onGuardRevokeApproval(m)
        is JcefBridge.Msg.GuardRemoveWhitelist -> onGuardRemoveWhitelist(m)
        is JcefBridge.Msg.GuardAllowAlways -> onGuardAllowAlways(m)
        JcefBridge.Msg.GuardLog -> panel.security.pushGuard()
        is JcefBridge.Msg.GuardExplain -> panel.guard.explain(m.id)
    }

    private fun onGuardRevokeApproval(m: JcefBridge.Msg.GuardRevokeApproval) {
        val rule = SecurityRule.from(m.rule)
        if (rule == null || m.command.isBlank()) {
            logger.warn("A bypass warning asked to revoke something this build cannot place: ${m.rule}")
            return
        }
        session.guardApprovals.revoke(rule, m.command.trim())
        JcefChatPanel.pushSettingsMenuToAll()
        session.systemNotice("`${m.command.trim()}` is no longer pre-approved. ${rule.label} decides again.")
    }

    private fun onSettingsToggle(m: JcefBridge.Msg.SettingsToggle) {
        if (!writeSettingsToggle(m)) {
            logger.warn("The chat's settings menu asked for a switch this build does not have: ${m.key}")
            return
        }
        JcefChatPanel.pushSettingsMenuToAll()
    }

    private fun writeSettingsToggle(m: JcefBridge.Msg.SettingsToggle): Boolean {
        val settings = ClaudeSettings.getInstance(panel.project)
        JcefSettingsMenu.alwaysAllowTool(m.key)?.let { tool ->
            if (m.on) settings.alwaysAllow.remember(tool) else settings.alwaysAllow.forget(tool)
            return true
        }
        JcefSettingsMenu.sessionApproval(m.key)?.let { (rule, command) ->
            if (!m.on) session.guardApprovals.revoke(rule, command)
            return true
        }
        val models = session.models.map { it.value }
        var known = false
        settings.update { known = JcefSettingsMenu.apply(it, m.key, m.on, models) }
        if (known) JcefSettingsMenu.applyToSession(session, m.key, m.on)
        return known
    }

    private fun onRequestCard(m: JcefBridge.Msg.RequestCard) = when (m) {
        is JcefBridge.Msg.ResolvePermission -> onResolvePermission(m)

        is JcefBridge.Msg.ResolveQuestion -> cardSession(m.scope).cards.resolveQuestion(m.id, m.answers)

        is JcefBridge.Msg.ResolveElicitation ->
            cardSession(m.scope).cards.resolveElicitation(m.id, m.action, m.content)

        is JcefBridge.Msg.AlwaysAllow -> onAlwaysAllow(m)
    }

    private fun cardSession(scope: String): ClaudeSession =
        if (scope == JcefBridge.SCOPE_GIT) panel.gitChat.session() else session

    private fun withStrip(what: String, block: (ChatTabsPanel) -> Unit) {
        val strip = panel.chatStrip()
        if (strip == null) {
            logger.warn("Claude Code: no chat strip to $what — the press was dropped")
            return
        }
        block(strip)
    }

    private fun onResolvePermission(m: JcefBridge.Msg.ResolvePermission) {
        val target = cardSession(m.scope)
        val wasPlan = target.cards.pending().firstOrNull { it.requestId == m.id }?.isPlan == true
        target.cards.resolvePermission(m.id, m.allow)
        if (wasPlan && m.allow && target === session) panel.feed.requestPlan()
    }

    private fun onGitAction(m: JcefBridge.Msg.GitAction) {
        val chat = { panel.gitChat.session() }
        GitIntegration.getInstance(panel.project).perform(m.id, m.hash, chat) { panel.pushGit() }
        if (GitActionCatalog.byId(m.id)?.kind == GitActionCatalog.Kind.PROMPT) panel.gitChat.show()
    }

    private fun onSetWorkloadWindow(minutes: Int) {
        if (minutes !in WorkloadWindow.WINDOW_MINUTES) {
            logger.warn("Workloads view asked for a window this build does not offer: $minutes")
            return
        }
        ClaudeSettings.getInstance(panel.project).update { it.workloadWindowMinutes = minutes }
        JcefChatPanel.pushSessionToAll()
    }

    private fun onGuardSuspend(m: JcefBridge.Msg.GuardSuspend) {
        val rule = SecurityRule.from(m.rule)
        val duration = SecuritySuspensions.Duration.from(m.duration)
        if (rule == null || duration == null) {
            logger.warn("A guard block asked to suspend something this build does not have: ${m.rule}/${m.duration}")
            return
        }
        val settings = ClaudeSettings.getInstance(panel.project)
        when (duration) {
            SecuritySuspensions.Duration.FOREVER ->
                settings.update { JcefSettingsMenu.apply(it, "rule:${rule.name}", false, session.models.map { p -> p.value }) }

            SecuritySuspensions.Duration.UNTIL_IDE_CLOSES -> SecuritySuspensions.suspendUntilIdeCloses(rule)

            else -> settings.update {
                it.securityRuleSuspensions = SecuritySuspensions.withSuspension(
                    it.securityRuleSuspensions,
                    rule,
                    duration.millis ?: 0,
                    System.currentTimeMillis(),
                )
            }
        }
        JcefChatPanel.pushSettingsMenuToAll()
        session.systemNotice(
            "${rule.label} is disabled ${duration.phrase}. Matching calls will ask you instead of being refused.",
        )
    }

    private fun onGuardMaster(m: JcefBridge.Msg.GuardMaster) {
        val settings = ClaudeSettings.getInstance(panel.project)
        if (m.on) {
            settings.update { SecuritySuspensions.guardOn(it) }
            announceGuard("The Sensitive Guard is back on. Every tool call is judged again.")
            return
        }
        val duration = SecuritySuspensions.Duration.from(m.duration)
        if (duration == null) {
            logger.warn("The shield asked to stand down for a duration this build does not have: ${m.duration}")
            return
        }
        settings.update { SecuritySuspensions.guardOff(it, duration, System.currentTimeMillis()) }
        announceGuard(
            "The Sensitive Guard is off ${duration.phrase}. Nothing is being judged — no rule, no card, " +
                "no block — until it comes back on.",
        )
    }

    private fun announceGuard(notice: String) {
        JcefChatPanel.pushSettingsMenuToAll()
        JcefChatPanel.pushStateToAll()
        session.systemNotice(notice)
    }

    private fun onGuardWhitelist(m: JcefBridge.Msg.GuardWhitelist) {
        val rule = SecurityRule.from(m.rule)
        val command = m.command.trim()
        if (rule == null || command.isEmpty()) {
            logger.warn("A guard block asked to whitelist something this build cannot place: ${m.rule}")
            return
        }
        val settings = ClaudeSettings.getInstance(panel.project)
        if (!GuardWhitelistPrompt.confirm(panel.project, rule, command)) return
        val policy = settings.sensitivePolicy(panel.project.basePath)
        val canonical = SensitiveGuard.canonicalCommand(command, policy)
        val already = GuardWhitelists.byRule(settings.state.securityRuleWhitelists)[rule].orEmpty()
            .plus(GuardWhitelists.byCategory(settings.state.securityCategoryWhitelists)[rule.category].orEmpty())
            .plus(GuardWhitelists.commands(settings.state.securityCommandWhitelist))
            .any { SensitiveGuard.canonicalCommand(it, policy) == canonical }
        if (already) {
            session.systemNotice("`$command` is already whitelisted — nothing added.")
            return
        }
        settings.update {
            it.securityRuleWhitelists = GuardWhitelists.withEntry(it.securityRuleWhitelists, rule.name, command)
        }
        JcefChatPanel.pushSettingsMenuToAll()
        session.systemNotice("`$command` is whitelisted for ${rule.label}. Every other rule still judges it.")
    }

    private fun onGuardRemoveWhitelist(m: JcefBridge.Msg.GuardRemoveWhitelist) {
        val rule = SecurityRule.from(m.rule)
        if (rule == null || m.command.isBlank()) {
            logger.warn("A bypass warning asked to un-whitelist something this build cannot place: ${m.rule}")
            return
        }
        val settings = ClaudeSettings.getInstance(panel.project)
        val policy = settings.sensitivePolicy(panel.project.basePath)
        val wanted = SensitiveGuard.canonicalCommand(m.command, policy)
        val same = { entry: String -> SensitiveGuard.canonicalCommand(entry, policy) == wanted }

        val removedFrom = removeWhitelisted(settings, rule, same)
        if (removedFrom == null) {
            session.systemNotice("`${m.command.trim()}` is not on any whitelist any more.")
            return
        }
        JcefChatPanel.pushSettingsMenuToAll()
        session.systemNotice("`${m.command.trim()}` is off the $removedFrom. ${rule.label} decides it again.")
    }

    private fun removeWhitelisted(
        settings: ClaudeSettings,
        rule: SecurityRule,
        same: (String) -> Boolean,
    ): String? {
        val state = settings.state
        return when {
            GuardWhitelists.holds(state.securityRuleWhitelists, rule.name, same) -> {
                settings.update { it.securityRuleWhitelists = GuardWhitelists.without(it.securityRuleWhitelists, rule.name, same) }
                "whitelist for ${rule.label}"
            }

            GuardWhitelists.holds(state.securityCategoryWhitelists, rule.category.name, same) -> {
                settings.update {
                    it.securityCategoryWhitelists =
                        GuardWhitelists.without(it.securityCategoryWhitelists, rule.category.name, same)
                }
                "whitelist for ${rule.category.label}"
            }

            GuardWhitelists.holds(state.securityCommandWhitelist, null, same) -> {
                settings.update { it.securityCommandWhitelist = GuardWhitelists.without(it.securityCommandWhitelist, null, same) }
                "whitelist that applies everywhere"
            }

            else -> null
        }
    }

    private fun onGuardAllowAlways(m: JcefBridge.Msg.GuardAllowAlways) {
        val chat = cardSession(m.scope)
        val target = chat.cards.pending().firstOrNull { it.requestId == m.id } ?: return
        val rule = target.guard?.rule ?: return
        chat.guardApprovals.approve(rule, ToolInputScanner.commandText(target.input))
        chat.cards.resolvePermission(target.requestId, true)
    }

    private fun onAlwaysAllow(m: JcefBridge.Msg.AlwaysAllow) {
        ClaudeSettings.getInstance(panel.project).alwaysAllow.remember(m.tool)
        val chat = cardSession(m.scope)
        val pending = chat.cards.pending()
        val target = pending.firstOrNull { it.requestId == m.id }
            ?: pending.firstOrNull { it.toolName == m.tool }
        target?.let { chat.cards.resolvePermission(it.requestId, true) }
    }

    private fun onDiffs(m: JcefBridge.Msg.Diffs) = when (m) {
        is JcefBridge.Msg.ViewDiff -> {
            cardSession(m.scope).cards.pending().firstOrNull { it.requestId == m.id }
                ?.let { DiffPresenter.openDiff(panel.project, it.toolName, it.input) }
            Unit
        }

        is JcefBridge.Msg.ViewDiffByTool -> {
            session.cards.editSnapshot(m.toolUseId)?.let {
                DiffPresenter.openDiff(panel.project, it.toolName, it.input, it.beforeText)
            }
            Unit
        }

        is JcefBridge.Msg.RevertEdit -> panel.edits.rewindOrRevert(m.toolUseId)

        is JcefBridge.Msg.Open -> panel.links.open(m.url)

        is JcefBridge.Msg.ResolveLinks -> resolveLinksOffEdt(m)
    }

    private fun onAttachments(m: JcefBridge.Msg.Attachments) = when (m) {
        is JcefBridge.Msg.RemoveAttachment -> tray.remove(m.id)

        JcefBridge.Msg.AttachSelection -> {
            tray.addSelection()
            Unit
        }

        JcefBridge.Msg.AttachCurrentFile -> tray.addCurrentFile()

        JcefBridge.Msg.RequestAttachData -> tray.pushMenuData()

        is JcefBridge.Msg.AttachPath -> tray.addPath(m.path)

        is JcefBridge.Msg.TreeChildren -> onTreeChildren(m)

        is JcefBridge.Msg.TreeExpand -> onTreeExpand(m)

        is JcefBridge.Msg.AttachPaths -> onAttachPaths(m.paths)

        JcefBridge.Msg.PasteClipboard -> tray.pasteFromClipboard()

        is JcefBridge.Msg.PasteClipboardImage -> tray.pasteImageFromClipboard(m.notify)

        is JcefBridge.Msg.Attach -> onAttachImage(m)
    }

    private fun onAttachImage(m: JcefBridge.Msg.Attach) {
        val image = ImageAttachments.fromWebPayload(m.name, m.mediaType, m.base64)
        if (image == null) {
            tray.notify(
                "That attachment was not added: only PNG, JPEG, GIF and WebP images are accepted, " +
                    "up to ${ImageAttachments.MAX_IMAGE_BYTES / BYTES_PER_MB} MB.",
            )
            return
        }
        tray.add(image)
    }

    private fun projectTreeMode(wire: String): ProjectTree.Mode? = when (wire) {
        "files" -> ProjectTree.Mode.FILES
        "directories" -> ProjectTree.Mode.DIRECTORIES
        else -> null
    }

    private fun onTreeChildren(m: JcefBridge.Msg.TreeChildren) {
        val mode = projectTreeMode(m.mode) ?: return unknownTreeMode(m.mode)
        pushOffEdt("window.cc.treeChildren") {
            JcefTreeData.childrenJson(m.path, m.mode, ProjectTree.children(panel.project, m.path, mode))
        }
    }

    private fun onTreeExpand(m: JcefBridge.Msg.TreeExpand) {
        val mode = projectTreeMode(m.mode) ?: return unknownTreeMode(m.mode)
        pushOffEdt("window.cc.treeExpansion") {
            JcefTreeData.expansionJson(m.path, m.mode, ProjectTree.expand(panel.project, m.path, mode))
        }
    }

    private fun onAttachPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        val root = panel.project.basePath
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val wanted = paths.take(ProjectTree.MAX_ENTRIES)
            val files = wanted.mapNotNull { ProjectTree.resolve(root, it)?.path }
            if (files.size != wanted.size) {
                val lost = wanted.size - files.size
                logger.warn("Claude Code: $lost of ${wanted.size} attached paths name nothing inside this project")
            }
            if (files.isEmpty()) return@executeOnPooledThread
            app.invokeLater({ tray.addPaths(files) }, ModalityState.any())
        }
    }

    private fun unknownTreeMode(wire: String) =
        logger.warn("The attach menu asked to browse the project in a mode this build does not have: $wire")

    private fun pushOffEdt(method: String, build: () -> JsonObject) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val payload = runCatching(build)
                .onFailure { logger.warn("Claude Code: $method could not be answered", it) }
                .getOrNull() ?: return@executeOnPooledThread
            app.invokeLater({ panel.host.exec("$method && $method($payload)") }, ModalityState.any())
        }
    }

    private fun onSessionControl(m: JcefBridge.Msg.SessionControl) = when (m) {
        is JcefBridge.Msg.McpReconnect -> {
            session.queries.reconnectMcp(m.name)
            feed.requestMcp()
        }

        is JcefBridge.Msg.McpToggle -> {
            session.queries.toggleMcp(m.name, m.enabled)
            feed.requestMcp()
        }

        is JcefBridge.Msg.StopTask -> session.queries.stopTask(m.taskId)

        is JcefBridge.Msg.SetWorkloadWindow -> onSetWorkloadWindow(m.minutes)

        is JcefBridge.Msg.GitAction -> onGitAction(m)

        JcefBridge.Msg.NewChat -> ClaudeToolWindowFactory.newChat(panel.project)

        JcefBridge.Msg.CloseThisChat -> withStrip("close this chat") { strip ->
            strip.tabFor(session)?.let { strip.close(it) }
        }

        JcefBridge.Msg.OpenGitView -> ClaudeToolWindowFactory.showGitView(panel.project)

        else -> {
            if (!onVuln(m) && !onNavigation(m) && !panel.onboarding.handle(m)) {
                logger.warn("unhandled session-control message: $m")
            }
            Unit
        }
    }

    private fun vuln(): VulnService = VulnService.getInstance(panel.project)

    private fun onVuln(m: JcefBridge.Msg.SessionControl): Boolean {
        when (m) {
            JcefBridge.Msg.OpenVulnView -> panel.security.showVulnView()
            is JcefBridge.Msg.VulnConsentChoice -> vuln().setConsent(m.granted) { panel.pushSession() }
            JcefBridge.Msg.VulnScan -> vuln().scan { panel.pushSession() }
            JcefBridge.Msg.VulnCancel -> vuln().cancel { panel.pushSession() }
            JcefBridge.Msg.VulnInventoryRequest -> onVulnInventory(vuln())
            is JcefBridge.Msg.VulnFix -> onVulnFix(vuln(), m.findingId)
            else -> return false
        }
        return true
    }

    private fun onVulnInventory(service: VulnService) {
        val endpoint = service.snapshot().endpoint
        pushOffEdt("window.cc.vulnInventory") { JcefVulnData.inventoryJson(service.inventory(), endpoint) }
    }

    private fun onVulnFix(service: VulnService, findingId: String) {
        val finding = service.finding(findingId)
        if (finding == null) {
            logger.warn("The security view asked to fix a finding that is no longer in the last report: $findingId")
            return
        }
        val text = VulnPromptedActions.updatePrompt(finding)
        if (text == null) {
            logger.warn("Refusing to prompt for '$findingId': the advisory or the manifest carries unquotable text")
            return
        }
        session.send(text)
    }

    private fun onNavigation(m: JcefBridge.Msg.SessionControl): Boolean {
        when (m) {
            is JcefBridge.Msg.RevealAgent -> panel.agentTabs.revealElsewhere(m.chatId) { it.agentTabs.revealFromHost(m) }

            is JcefBridge.Msg.RevealBackgroundTask ->
                panel.agentTabs.revealElsewhere(m.chatId) { it.transcript.showBackgroundTask(m.taskId) }

            is JcefBridge.Msg.ShowChatTranscript -> panel.transcript.showTranscript(null)

            is JcefBridge.Msg.SelectChat -> withStrip("select chat ${m.chatId}") { it.selectById(m.chatId) }

            is JcefBridge.Msg.CloseChat -> withStrip("close chat ${m.chatId}") { it.closeById(m.chatId) }

            is JcefBridge.Msg.SelectAgent -> panel.transcript.showTranscript(m.agentId.ifBlank { null })

            is JcefBridge.Msg.CloseAgent -> panel.agentTabs.closeAgent(m.agentId)

            else -> return false
        }
        return true
    }

    private fun onLifecycle(m: JcefBridge.Msg.Lifecycle) = when (m) {
        JcefBridge.Msg.Ready -> {
            panel.host.markWebReady()
            panel.pushTheme()
            panel.pushSettingsMenu()
            panel.pushMetaState()
            panel.pushPermissions()
            tray.push()
            panel.pushSession()
            feed.requestMcp()
            feed.requestVersion()
            panel.agentTabs.render()
            panel.pushGit()
            panel.security.pushGuard()
            panel.security.pushVuln()
            panel.transcript.fullResync()
        }

        is JcefBridge.Msg.Diagnostics ->
            if (m.report.startsWith("uncaught ")) {
                logger.warn("Claude Code chat page: ${m.report}")
            } else {
                logger.info("JCEF diagnostics: ${m.report}")
            }

        is JcefBridge.Msg.Unknown -> {}
    }

    private fun dispatchSend(raw: String) {
        session.clearSuggestion()
        val atts = tray.take()
        val text = raw.trim()
        when {
            atts.isEmpty() && text == "/login" -> session.login.start()

            atts.isEmpty() && BTW.matches(text.substringBefore('\n')) -> {
                val rest = text.removePrefix("/btw").trim()
                session.sendSideQuestion(rest)
            }

            else -> session.send(raw, atts)
        }
    }

    private fun resolveLinksOffEdt(m: JcefBridge.Msg.ResolveLinks) {
        if (m.paths.isEmpty() && m.symbols.isEmpty()) return
        val project = panel.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = runCatching {
                LinkResolver.resolvePaths(project, m.paths) + LinkResolver.resolveSymbols(project, m.symbols)
            }.getOrDefault(emptyList())
            if (resolved.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                panel.host.exec(
                    "window.cc.links && window.cc.links(" + JcefTranscriptPayload.linksJson(m.rowId, resolved) + ")",
                )
            }, ModalityState.any())
        }
    }

    private companion object {
        private val logger = Logger.getInstance(ChatBridgeRouter::class.java)

        private val BTW = Regex("^/btw\\b.*")

        private const val BYTES_PER_MB = 1024 * 1024
    }
}
