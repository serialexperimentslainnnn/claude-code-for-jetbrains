package dev.lain.claudejb.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Native look that follows the **IDE theme** (light/dark) like the JetBrains AI Assistant: surfaces, text and
 * borders come from the platform ([UIUtil]/[JBColor]/editor scheme) so the chat blends into the IDE. Only the
 * Claude **coral accent** stays fixed — it's the product's identity (Send button, avatar, links, selection).
 *
 * Colors are computed properties resolved from the platform when each component is built (i.e. they reflect
 * the IDE theme in effect when the chat is opened/rebuilt).
 */
object ChatTheme {
    val BG: Color get() = UIUtil.getPanelBackground()                       // transcript / composer surface
    val USER_BUBBLE: Color get() = editorBg()                              // the user's message block
    val CARD_BG: Color get() = editorBg()                                 // tool calls, composer card
    val CARD_HOVER: Color get() = ColorUtil.brighter(editorBg(), 1)
    val CODE_BG: Color get() = editorBg()                                 // fenced code background
    val TEXT: Color get() = UIUtil.getLabelForeground()                   // primary text
    val TEXT_DIM: Color get() = NamedColorUtil.getInactiveTextColor()     // secondary / metadata
    val ACCENT = Color(0xD97757)                                          // Claude coral — links, send, avatar
    val ACCENT_TEXT = Color(0xFFFFFF)
    val BORDER: Color get() = JBColor.border()
    val ERROR: Color get() = NamedColorUtil.getErrorForeground()
    val ERROR_BG: Color get() = JBColor(Color(0xFBE9E7), Color(0x3D2422))

    /** The Claude starburst, used as the assistant avatar. */
    val avatar: Icon = IconLoader.getIcon("/icons/claude.svg", ChatTheme::class.java)

    val body: JBFont get() = JBFont.label()
    val small: JBFont get() = JBFont.medium()
    val mono: JBFont get() = JBFont.create(java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, JBFont.label().size))

    private fun editorBg(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

    fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /** A panel that paints a rounded background (and optional border), for bubbles and cards. */
    open class RoundedPanel(
        private val arc: Int,
        private val fill: Color?,
        private val line: Color? = null,
    ) : JPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val a = JBUI.scale(arc)
                if (fill != null) {
                    g2.color = fill
                    g2.fillRoundRect(0, 0, width, height, a, a)
                }
                if (line != null) {
                    g2.color = line
                    g2.drawRoundRect(0, 0, width - 1, height - 1, a, a)
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
