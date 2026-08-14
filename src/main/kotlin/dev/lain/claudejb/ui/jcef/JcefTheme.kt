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

/**
 * Produces the flat CSS-variable theme map the JCEF web layer consumes.
 *
 * Every value is derived from the live IDE theme (panel/editor backgrounds, label/inactive foregrounds, the
 * platform border) so the embedded chat blends into the IDE in both light and dark schemes; only the Claude
 * coral accent stays fixed (product identity), mirroring [ChatTheme]. Keys are camelCase; core maps them to
 * kebab-case CSS custom properties on `:root`.
 *
 * All reads here are cheap, read-only platform getters that are safe off the paint thread.
 */
object JcefTheme {

    /** Alpha of the translucent accent/link fills (selection wash, link hover). */
    private const val ACCENT_SOFT_ALPHA = 0.16
    private const val LINK_SOFT_ALPHA = 0.14

    /** Base chat font size in unscaled px, before [JBUI.scale]. */
    private const val BASE_FONT_PX = 13

    // Relative luminance, ITU-R BT.601 coefficients — enough to tell a light theme from a dark one, which is
    // all [surfaceTwo] needs (no need for the sRGB-linearised BT.709 form used for contrast ratios).
    private const val LUMA_RED = 0.299
    private const val LUMA_GREEN = 0.587
    private const val LUMA_BLUE = 0.114

    /** 8-bit channel maximum, for normalising and for clamping. */
    private const val CHANNEL_MAX = 255

    /** Below this relative luminance the theme counts as dark, so the second surface lightens instead of darkens. */
    private const val DARK_THEME_LUMA = 0.5

    /** How far the second surface moves from the editor background: up on dark themes, down on light ones. */
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
        // Hyperlinks use the IDE's OWN link colour (the familiar blue, and correct in every theme) rather than the
        // product's coral accent — a jump-to-code link should look like a link, not like branding.
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
            // Reduced motion — the plugin's OWN setting, off by default. See ClaudeSettings.reduceMotion for
            // why neither the browser's media query nor the IDE's window-animation toggle could answer this.
            put("reducedMotion", reduceMotion)
        }
    }

    /** A slightly nudged variant of the editor background for secondary surfaces (cards, code heads). */
    private fun surfaceTwo(base: Color): Color {
        // Lighten on dark themes, darken on light themes, so the second surface always reads as a layer.
        val luma = (LUMA_RED * base.red + LUMA_GREEN * base.green + LUMA_BLUE * base.blue) / CHANNEL_MAX
        val nudge = if (luma < DARK_THEME_LUMA) SURFACE_NUDGE_UP else SURFACE_NUDGE_DOWN
        return Color(
            (base.red + nudge).coerceIn(0, CHANNEL_MAX),
            (base.green + nudge).coerceIn(0, CHANNEL_MAX),
            (base.blue + nudge).coerceIn(0, CHANNEL_MAX),
        )
    }

    /** "#rrggbb" for a Color. */
    private fun hex(c: Color): String = String.format(Locale.ROOT, "#%02x%02x%02x", c.red, c.green, c.blue)

    /**
     * "rgba(r,g,b,a)" for a translucent fill (alpha 0..1).
     *
     * [Locale.ROOT] is load-bearing, not defensive: the default-locale `"%.3f".format(0.16)` renders `0,160`
     * under any comma-decimal locale (es, de, fr…), which makes the value `rgba(r, g, b, 0,160)` — four
     * components instead of three, so the browser drops the declaration and the fill disappears. Same class of
     * bug as the locale-formatted token counts.
     */
    private fun rgba(c: Color, alpha: Double): String =
        "rgba(${c.red}, ${c.green}, ${c.blue}, ${String.format(Locale.ROOT, "%.3f", alpha)})"
}
