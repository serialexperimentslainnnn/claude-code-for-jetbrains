package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
internal class BannerRegistry(private val project: Project) {

    class Banner(val text: String, val actions: List<String>, @Volatile var chosen: String? = null)

    private val banners = ConcurrentHashMap<String, Banner>()

    fun show(file: VirtualFile, text: String, actions: List<String>) {
        banners[file.path] = Banner(text, actions)
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    fun clear(file: VirtualFile): Banner? {
        val banner = banners.remove(file.path)
        EditorNotifications.getInstance(project).updateNotifications(file)
        return banner
    }

    fun bannerOf(file: VirtualFile): Banner? = banners[file.path]

    fun choose(file: VirtualFile, action: String) {
        banners[file.path]?.chosen = action
        EditorNotifications.getInstance(project).updateNotifications(file)
    }
}

internal class BannerProvider : EditorNotificationProvider {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val registry = project.getService(BannerRegistry::class.java)
        val banner = registry.bannerOf(file)?.takeIf { it.chosen == null } ?: return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = banner.text
                banner.actions.forEach { action -> createActionLabel(action) { registry.choose(file, action) } }
                createActionLabel(DISMISS) { registry.clear(file) }
            }
        }
    }

    private companion object {
        const val DISMISS = "Dismiss"
    }
}
