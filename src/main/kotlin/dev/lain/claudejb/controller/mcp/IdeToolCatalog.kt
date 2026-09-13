package dev.lain.claudejb.controller.mcp

import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.git.GitAvailability
import dev.lain.claudejb.controller.mcp.tools.code.AnalysisTools
import dev.lain.claudejb.controller.mcp.tools.code.AnalyzeTools
import dev.lain.claudejb.controller.mcp.tools.code.BookmarkTools
import dev.lain.claudejb.controller.mcp.tools.code.DiagnosticsTools
import dev.lain.claudejb.controller.mcp.tools.code.EditOpsTools
import dev.lain.claudejb.controller.mcp.tools.code.EditTools
import dev.lain.claudejb.controller.mcp.tools.code.EditorTools
import dev.lain.claudejb.controller.mcp.tools.code.FileTools
import dev.lain.claudejb.controller.mcp.tools.code.FormatTools
import dev.lain.claudejb.controller.mcp.tools.code.HierarchyTools
import dev.lain.claudejb.controller.mcp.tools.code.IndexTools
import dev.lain.claudejb.controller.mcp.tools.code.InspectTools
import dev.lain.claudejb.controller.mcp.tools.code.JavaAvailability
import dev.lain.claudejb.controller.mcp.tools.code.LanguageTools
import dev.lain.claudejb.controller.mcp.tools.code.MarkupTools
import dev.lain.claudejb.controller.mcp.tools.code.NavigateTools
import dev.lain.claudejb.controller.mcp.tools.code.OutlineTools
import dev.lain.claudejb.controller.mcp.tools.code.PresenceTools
import dev.lain.claudejb.controller.mcp.tools.code.PsiTools
import dev.lain.claudejb.controller.mcp.tools.code.ReadTools
import dev.lain.claudejb.controller.mcp.tools.code.RecentTools
import dev.lain.claudejb.controller.mcp.tools.code.RefactorOpsTools
import dev.lain.claudejb.controller.mcp.tools.code.RefactorTools
import dev.lain.claudejb.controller.mcp.tools.code.SearchTools
import dev.lain.claudejb.controller.mcp.tools.code.TemplateTools
import dev.lain.claudejb.controller.mcp.tools.code.UastTools
import dev.lain.claudejb.controller.mcp.tools.code.ViewTools
import dev.lain.claudejb.controller.mcp.tools.code.WorkspaceTools
import dev.lain.claudejb.controller.mcp.tools.ops.ActionTools
import dev.lain.claudejb.controller.mcp.tools.ops.ConsoleTools
import dev.lain.claudejb.controller.mcp.tools.ops.DbTools
import dev.lain.claudejb.controller.mcp.tools.ops.HttpTools
import dev.lain.claudejb.controller.mcp.tools.ops.IdeTools
import dev.lain.claudejb.controller.mcp.tools.ops.NotifyTools
import dev.lain.claudejb.controller.mcp.tools.ops.ProjectTools
import dev.lain.claudejb.controller.mcp.tools.ops.RemoteTools
import dev.lain.claudejb.controller.mcp.tools.ops.ServiceTools
import dev.lain.claudejb.controller.mcp.tools.ops.ServiceViewTools
import dev.lain.claudejb.controller.mcp.tools.ops.SshTools
import dev.lain.claudejb.controller.mcp.tools.ops.ToolsMenuTools
import dev.lain.claudejb.controller.mcp.tools.ops.WindowTools
import dev.lain.claudejb.controller.mcp.tools.run.BreakpointTools
import dev.lain.claudejb.controller.mcp.tools.run.BuildTools
import dev.lain.claudejb.controller.mcp.tools.run.DebugTools
import dev.lain.claudejb.controller.mcp.tools.run.RunOpsTools
import dev.lain.claudejb.controller.mcp.tools.run.RunTools
import dev.lain.claudejb.controller.mcp.tools.run.TerminalTools
import dev.lain.claudejb.controller.mcp.tools.run.TestTools
import dev.lain.claudejb.controller.mcp.tools.vcs.ChangesTools
import dev.lain.claudejb.controller.mcp.tools.vcs.ForgeTools
import dev.lain.claudejb.controller.mcp.tools.vcs.GitReadTools
import dev.lain.claudejb.controller.mcp.tools.vcs.GitWriteTools
import dev.lain.claudejb.controller.mcp.tools.vcs.HistoryTools
import dev.lain.claudejb.controller.mcp.tools.vcs.LogOpsTools
import dev.lain.claudejb.controller.mcp.tools.vcs.PullRequestOpsTools
import dev.lain.claudejb.controller.mcp.tools.vcs.ReleaseTools
import dev.lain.claudejb.model.mcp.ToolCatalog
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.session.launch.IdeServer
import kotlinx.coroutines.CoroutineScope

internal object IdeToolCatalog {

    private val DOMAINS: Map<IdeServer, List<(Project, CoroutineScope) -> ToolDomain?>> = mapOf(
        IdeServer.CODE to listOf(
            { p, _ -> ReadTools(p, Reveal(p)).domain() },
            { p, _ -> SearchTools(p).domain() },
            { p, _ -> NavigateTools(p).domain() },
            { p, _ -> OutlineTools(p).domain() },
            { p, _ -> DiagnosticsTools(p, Reveal(p)).domain() },
            { p, _ -> InspectTools(p).domain() },
            { p, _ -> EditTools(p, Reveal(p)).domain() },
            { p, s -> EditOpsTools(p, Reveal(p), IdeActions(p, s)).domain() },
            { p, _ -> RefactorTools(p).domain() },
            { p, _ -> FormatTools(p).domain() },
            { p, s -> EditorTools(p, Reveal(p), IdeActions(p, s)).domain() },
            { p, _ -> HierarchyTools(p).domain() },
            { p, s -> RecentTools(p, IdeActions(p, s)).domain() },
            { p, s -> AnalyzeTools(p, IdeActions(p, s)).domain() },
            { p, s -> AnalysisTools(p, IdeActions(p, s)).domain() },
            { p, s -> ViewTools(p, IdeActions(p, s)).domain() },
            { p, s -> FileTools(p, IdeActions(p, s), Reveal(p)).domain() },
            { p, s -> RefactorOpsTools(IdeActions(p, s)).domain() },
            { p, _ -> TemplateTools(p, TargetContext(p), Reveal(p)).domain() },
            { p, s -> LanguageTools(p, IdeActions(p, s)).domain() },
            { p, _ -> BookmarkTools(p, Reveal(p)).domain() },
            { p, _ -> PsiTools(p, Reveal(p)).domain() },
            { p, _ -> IndexTools(p).domain() },
            { p, _ -> if (JavaAvailability.isEnabled()) UastTools(p).domain() else null },
            { p, _ -> WorkspaceTools(p).domain() },
            { p, _ -> MarkupTools(p, TargetContext(p), Reveal(p)).domain() },
            { p, _ -> PresenceTools(p, Reveal(p)).domain() },
        ),
        IdeServer.RUN to listOf(
            { p, s -> BuildTools(p, s).domain() },
            { p, s -> RunTools(p, s).domain() },
            { p, s -> TestTools(p, s).domain() },
            { p, s -> TerminalTools(p, s).domain() },
            { p, _ -> DebugTools(p).domain() },
            { p, _ -> BreakpointTools(p).domain() },
            { p, s -> RunOpsTools(p, IdeActions(p, s), Reveal(p)).domain() },
        ),
        IdeServer.VCS to listOf(
            { p, _ -> GitReadTools(p, Reveal(p)).domain() },
            { p, _ -> GitWriteTools(p).domain() },
            { p, s -> ForgeTools(p, IdeActions(p, s), Reveal(p)).domain() },
            { p, s -> LogOpsTools(p, IdeActions(p, s)).domain() },
            { p, s -> ChangesTools(p, IdeActions(p, s), Reveal(p)).domain() },
            { p, s -> HistoryTools(p, IdeActions(p, s), Reveal(p)).domain() },
            { p, _ -> PullRequestOpsTools(p, Reveal(p)).domain() },
            { p, _ -> ReleaseTools(p).domain() },
        ),
        IdeServer.OPS to listOf(
            { p, s -> ServiceTools(p, s).domain() },
            { p, _ -> ProjectTools(p).domain() },
            { p, s -> IdeTools(p, IdeActions(p, s), s).domain() },
            { p, s -> ActionTools(p, IdeActions(p, s)).domain() },
            { p, s -> WindowTools(p, IdeActions(p, s)).domain() },
            { p, s -> ServiceViewTools(p, s).domain() },
            { p, s -> RemoteTools(p, IdeActions(p, s), Reveal(p)).domain() },
            { p, s -> ToolsMenuTools(p, IdeActions(p, s)).domain() },
            { p, s -> ConsoleTools(p, IdeActions(p, s)).domain() },
            { p, _ -> NotifyTools(p).domain() },
            { p, _ -> DbTools(p).domain() },
            { p, s -> HttpTools(p, s, Reveal(p)).takeIf { it.available() }?.domain() },
            { p, _ -> SshTools(p).takeIf { it.available() }?.domain() },
        ),
    )

    private val REQUIRES: Map<IdeServer, () -> Boolean> = mapOf(IdeServer.VCS to GitAvailability::isGitPluginEnabled)

    fun catalog(server: IdeServer, project: Project, scope: CoroutineScope): ToolCatalog {
        if (REQUIRES[server]?.invoke() == false) return ToolCatalog(emptyList())
        return ToolCatalog(DOMAINS[server].orEmpty().mapNotNull { it(project, scope) })
    }
}
