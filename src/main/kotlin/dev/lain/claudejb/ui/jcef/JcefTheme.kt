package dev.lain.claudejb.ui.jcef

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import dev.lain.claudejb.ui.ChatTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color
import java.util.Locale

object JcefTheme {

    private const val ACCENT_SOFT_ALPHA = 0.16
    private const val LINK_SOFT_ALPHA = 0.14

    private const val BASE_FONT_PX = 13

    private const val LUMA_RED = 0.299
    private const val LUMA_GREEN = 0.587
    private const val LUMA_BLUE = 0.114

    private const val CHANNEL_MAX = 255

    private const val DARK_THEME_LUMA = 0.5

    private const val SURFACE_NUDGE_UP = 18
    private const val SURFACE_NUDGE_DOWN = -12

    fun vars(reduceMotion: Boolean = false): JsonObject {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val editorBg = scheme.defaultBackground
        val panelBg = UIUtil.getPanelBackground()
        val text = UIUtil.getLabelForeground()
        val dim = NamedColorUtil.getInactiveTextColor()
        val border = JBColor.border()
        val accent = ChatTheme.ACCENT
        val danger = NamedColorUtil.getErrorForeground()
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED

        val labelFont = UIUtil.getLabelFont()
        val monoFamily = scheme.getFont(EditorFontType.PLAIN)?.family
            ?.takeIf { it.isNotBlank() }
            ?: "JetBrains Mono"

        return buildJsonObject {
            put("bg", hex(panelBg))
            put("surface", hex(editorBg))
            put("surface2", hex(surfaceTwo(editorBg)))
            put("text", hex(text))
            put("dim", hex(dim))
            put("border", hex(border))
            put("accent", hex(accent))
            put("accentSoft", rgba(accent, ACCENT_SOFT_ALPHA))
            put("link", hex(link))
            put("linkSoft", rgba(link, LINK_SOFT_ALPHA))
            put("codeBg", hex(editorBg))
            put("success", "#2e9e4f")
            put("warning", "#c9920a")
            put("danger", hex(danger))
            put("fontFamily", "\"${labelFont.family}\", system-ui, sans-serif")
            put("monoFamily", "\"$monoFamily\", \"JetBrains Mono\", monospace")
            put("fontSize", "${JBUI.scale(BASE_FONT_PX)}px")
            val syn = { key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color ->
                hex(scheme.getAttributes(key)?.foregroundColor ?: fallback)
            }
            put("synKeyword", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD, accent))
            put("synString", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STRING, text))
            put("synComment", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT, dim))
            put("synNumber", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER, text))
            put("synFunction", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_DECLARATION, text))
            put("synType", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.CLASS_NAME, text))
            put("vibe", ChatTheme.vibeMode)
            put("reducedMotion", reduceMotion)
        }
    }

    private fun surfaceTwo(base: Color): Color {
        val luma = (LUMA_RED * base.red + LUMA_GREEN * base.green + LUMA_BLUE * base.blue) / CHANNEL_MAX
        val nudge = if (luma < DARK_THEME_LUMA) SURFACE_NUDGE_UP else SURFACE_NUDGE_DOWN
        return Color(
            (base.red + nudge).coerceIn(0, CHANNEL_MAX),
            (base.green + nudge).coerceIn(0, CHANNEL_MAX),
            (base.blue + nudge).coerceIn(0, CHANNEL_MAX),
        )
    }

    private fun hex(c: Color): String = String.format(Locale.ROOT, "#%02x%02x%02x", c.red, c.green, c.blue)

    private fun rgba(c: Color, alpha: Double): String =
        "rgba(${c.red}, ${c.green}, ${c.blue}, ${String.format(Locale.ROOT, "%.3f", alpha)})"
}
