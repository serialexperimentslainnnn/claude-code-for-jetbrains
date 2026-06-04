package dev.lain.claudejb.ui.jcef

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

/**
 * Produces the flat CSS-variable theme map the JCEF web layer consumes (see JCEF_CONTRACT §THEME).
 *
 * Every value is derived from the live IDE theme (panel/editor backgrounds, label/inactive foregrounds, the
 * platform border) so the embedded chat blends into the IDE in both light and dark schemes; only the Claude
 * coral accent stays fixed (product identity), mirroring [ChatTheme]. Keys are camelCase; core maps them to
 * kebab-case CSS custom properties on `:root`.
 *
 * All reads here are cheap, read-only platform getters that are safe off the paint thread.
 */
object JcefTheme {

    fun vars(): JsonObject {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val editorBg = scheme.defaultBackground
        val panelBg = UIUtil.getPanelBackground()
        val text = UIUtil.getLabelForeground()
        val dim = NamedColorUtil.getInactiveTextColor()
        val border = JBColor.border()
        val accent = ChatTheme.ACCENT
        val danger = NamedColorUtil.getErrorForeground()

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
            put("accentSoft", rgba(accent, 0.16))
            put("codeBg", hex(editorBg))
            put("success", "#2e9e4f")
            put("warning", "#c9920a")
            put("danger", hex(danger))
            put("fontFamily", "\"${labelFont.family}\", system-ui, sans-serif")
            put("monoFamily", "\"$monoFamily\", \"JetBrains Mono\", monospace")
            put("fontSize", "${JBUI.scale(13)}px")
            // Syntax-token colours straight from the IDE's editor scheme → the chat's code blocks
            // (highlight.js classes) match the IDE exactly, in any theme. Fall back to text/dim.
            val syn = { key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color ->
                hex(scheme.getAttributes(key)?.foregroundColor ?: fallback)
            }
            put("synKeyword", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD, accent))
            put("synString", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STRING, text))
            put("synComment", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT, dim))
            put("synNumber", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER, text))
            put("synFunction", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_DECLARATION, text))
            put("synType", syn(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.CLASS_NAME, text))
            // 🌈 Vibe Mode flag — the web layer toggles the rainbow loop on this (not a CSS var).
            put("vibe", ChatTheme.vibeMode)
        }
    }

    /** A slightly nudged variant of the editor background for secondary surfaces (cards, code heads). */
    private fun surfaceTwo(base: Color): Color {
        // Lighten on dark themes, darken on light themes, so the second surface always reads as a layer.
        val lum = (0.299 * base.red + 0.587 * base.green + 0.114 * base.blue) / 255.0
        val d = if (lum < 0.5) 18 else -12
        return Color(
            (base.red + d).coerceIn(0, 255),
            (base.green + d).coerceIn(0, 255),
            (base.blue + d).coerceIn(0, 255),
        )
    }

    /** "#rrggbb" for a Color. */
    private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /** "rgba(r,g,b,a)" for a translucent fill (alpha 0..1). */
    private fun rgba(c: Color, alpha: Double): String =
        "rgba(${c.red}, ${c.green}, ${c.blue}, ${"%.3f".format(alpha)})"
}
