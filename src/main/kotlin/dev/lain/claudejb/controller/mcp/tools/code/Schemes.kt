package dev.lain.claudejb.controller.mcp.tools.code

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.psi.codeStyle.CodeStyleSchemes
import dev.lain.claudejb.model.mcp.ToolException

internal class Schemes {

    class Listed(val current: String, val names: List<String>)

    fun list(kind: String): Listed = when (kind) {
        THEME -> Listed(LafManager.getInstance().currentUIThemeLookAndFeel?.name.orEmpty(), themes().map { it.name })

        COLOR -> EditorColorsManager.getInstance().let { Listed(it.globalScheme.displayName, it.allSchemes.map { s -> s.displayName }) }

        KEYMAP -> Listed(
            KeymapManager.getInstance().activeKeymap.presentableName,
            KeymapManagerEx.getInstanceEx().allKeymaps.map { it.presentableName },
        )

        else -> Listed(CodeStyleSchemes.getInstance().currentScheme.name, CodeStyleSchemes.getInstance().allSchemes.map { it.name })
    }

    fun set(kind: String, name: String) {
        when (kind) {
            THEME -> LafManager.getInstance().setCurrentUIThemeLookAndFeel(themes().firstOrNull { it.name == name } ?: missing(kind, name))

            COLOR -> EditorColorsManager.getInstance().let { manager ->
                manager.setGlobalScheme(manager.allSchemes.firstOrNull { it.displayName == name } ?: missing(kind, name))
            }

            KEYMAP -> KeymapManagerEx.getInstanceEx().let { manager ->
                manager.setActiveKeymap(manager.allKeymaps.firstOrNull { it.presentableName == name } ?: missing(kind, name))
            }

            else -> CodeStyleSchemes.getInstance().let { it.setCurrentScheme(it.findSchemeByName(name) ?: missing(kind, name)) }
        }
    }

    private fun themes() = LafManager.getInstance().installedThemes.toList()

    private fun missing(kind: String, name: String): Nothing =
        throw ToolException("no $kind scheme named $name; action=list shows the names")

    companion object {
        const val THEME = "theme"
        const val COLOR = "color"
        const val KEYMAP = "keymap"
        const val CODE_STYLE = "code_style"

        val KINDS: List<String> = listOf(THEME, COLOR, KEYMAP, CODE_STYLE)
    }
}
