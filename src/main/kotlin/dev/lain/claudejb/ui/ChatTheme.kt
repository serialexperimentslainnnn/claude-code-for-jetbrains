package dev.lain.claudejb.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.NamedColorUtil
import com.intellij.ui.scale.JBUIScale
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

    /** The Claude coral, fixed product identity. */
    private val CORAL = Color(0xD97757)

    /**
     * 🌈 **Vibe Mode** (a gag toggle): when on, [ACCENT] cycles the rainbow and everything that paints with it
     * live — the send button, the option pills, the prompt focus/vibe ring — shimmers through the spectrum. Off by
     * default; toggled from the composer. [vibeHue] is advanced by the composer's animation timer (0..1).
     */
    @Volatile var vibeMode: Boolean = false
        private set
    @Volatile var vibeHue: Float = 0f
        private set

    // Repaint hooks from each open ChatPanel. A SINGLE shared timer advances the hue and repaints every registered
    // panel in sync — so multiple open chat tabs don't each run their own 50ms timer (which would double the
    // animation speed and desync), and closing the tab that toggled Vibe Mode doesn't leave the others frozen on a
    // static rainbow with no driver. The timer runs only while at least one panel is registered AND vibing.
    private val vibeRepaints = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    // Faster rainbow (~1.8s per full cycle, coherent with the JCEF side in app-core.js vibeStep): 30ms tick × 0.018
    // hue step ≈ 55 steps per rotation.
    private val vibeTimer = javax.swing.Timer(30) {
        vibeHue = (vibeHue + 0.018f) % 1f
        vibeRepaints.forEach { it() }
    }

    /** A ChatPanel registers its repaint hook (on init) so it animates while Vibe Mode is on. */
    fun registerVibe(repaint: () -> Unit) {
        vibeRepaints.addIfAbsent(repaint)
        if (vibeMode && !vibeTimer.isRunning) vibeTimer.start()
    }

    /** A ChatPanel unregisters its hook (on dispose); the shared timer stops once nothing is left to animate. */
    fun unregisterVibe(repaint: () -> Unit) {
        vibeRepaints.remove(repaint)
        if (vibeRepaints.isEmpty()) vibeTimer.stop()
    }

    /** Flips Vibe Mode globally: starts/stops the shared timer and refreshes every registered panel immediately
     *  (so each tab's toggle glyph / tool-window icon / avatar layout re-syncs, not just the one that flipped it). */
    fun setVibeMode(on: Boolean) {
        if (vibeMode == on) return
        vibeMode = on
        if (on) { if (vibeRepaints.isNotEmpty()) vibeTimer.start() } else vibeTimer.stop()
        vibeRepaints.forEach { it() }
    }

    /** Claude coral — links, send, avatar. In Vibe Mode it becomes the animated rainbow hue. */
    val ACCENT: Color get() = if (vibeMode) Color(Color.HSBtoRGB(vibeHue % 1f, 0.85f, 1.0f)) else CORAL
    val ACCENT_TEXT = Color(0xFFFFFF)

    /** A rainbow colour offset [fraction] of the spectrum from the current [vibeHue] — for gradient vibe borders. */
    fun vibeColorAt(fraction: Float): Color = Color(Color.HSBtoRGB((vibeHue + fraction) % 1f, 0.85f, 1.0f))

    /**
     * Wraps a stencil [base] icon so that, while 🌈 Vibe Mode is on, every non-transparent pixel is retinted to the
     * live rainbow [ACCENT] (anti-aliased edges preserved via the source alpha); off, it paints [base] untouched.
     * The composer's repaint loop drives the animation. Cheap enough for the small composer/tool glyphs.
     */
    fun vibeIcon(base: Icon): Icon = object : Icon {
        private val w = base.iconWidth.coerceAtLeast(1)
        private val h = base.iconHeight.coerceAtLeast(1)
        // The base is a static stencil: render it ONCE to capture its pixels (we only need the alpha mask), then
        // reuse one output buffer and only re-run the recolor when the tint actually changed — so an animating
        // frame costs an int-array recolor, not an SVG re-render + fresh BufferedImage per icon per frame.
        private var srcArgb: IntArray? = null
        private var out: java.awt.image.BufferedImage? = null
        private var pxBuf: IntArray? = null
        private var cachedRgb = -1

        override fun getIconWidth() = base.iconWidth
        override fun getIconHeight() = base.iconHeight

        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            if (!vibeMode) { base.paintIcon(c, g, x, y); return }
            var src = srcArgb
            if (src == null) {
                val tmp = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val tg = tmp.createGraphics()
                base.paintIcon(c, tg, 0, 0)
                tg.dispose()
                src = IntArray(w * h).also { tmp.getRGB(0, 0, w, h, it, 0, w) }
                srcArgb = src
            }
            val t = ACCENT
            val rgb = (t.red shl 16) or (t.green shl 8) or t.blue
            val o = out ?: java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { out = it }
            if (rgb != cachedRgb) {
                val px = pxBuf ?: IntArray(w * h).also { pxBuf = it }
                for (i in src.indices) {
                    val a = src[i] ushr 24 and 0xFF
                    px[i] = if (a != 0) (a shl 24) or rgb else 0
                }
                o.setRGB(0, 0, w, h, px, 0, w)
                cachedRgb = rgb
            }
            g.drawImage(o, x, y, null)
        }
    }
    val BORDER: Color get() = JBColor.border()
    val ERROR: Color get() = NamedColorUtil.getErrorForeground()
    val ERROR_BG: Color get() = JBColor(Color(0xFBE9E7), Color(0x3D2422))
    val WARNING: Color get() = JBColor(Color(0xB8860B), Color(0xE0A030))     // amber — quota warning

    // Tool-call lifecycle box colours: loading (sky blue) · running (amber, pulses to sky) · finished (green).
    val TOOL_LOADING: Color get() = JBColor(Color(0x3B9DD6), Color(0x4FC3F7))  // azul celeste
    val TOOL_RUNNING: Color get() = JBColor(Color(0xC9920A), Color(0xE0A030))  // amber
    val TOOL_FINISHED: Color get() = JBColor(Color(0x2E9E4F), Color(0x66BB6A)) // green

    // Soft drop-shadow tint for cards (translucent black; lighter on light themes, deeper on dark).
    val SHADOW: Color get() = JBColor(Color(0, 0, 0, 28), Color(0, 0, 0, 60))

    // Inline unified-diff colors (added / removed), GitHub-style, themed for light & dark.
    val DIFF_ADDED_FG: Color get() = JBColor(Color(0x22863A), Color(0x7EE787))
    val DIFF_ADDED_BG: Color get() = JBColor(Color(0xE6FFEC), Color(0x12261C))
    val DIFF_REMOVED_FG: Color get() = JBColor(Color(0xB31D28), Color(0xFFA198))
    val DIFF_REMOVED_BG: Color get() = JBColor(Color(0xFFEEF0), Color(0x301A1D))
    val DIFF_HUNK_FG: Color get() = NamedColorUtil.getInactiveTextColor()    // @@ … @@ hunk headers

    /** The Claude starburst, used as the assistant avatar — or, in 🌈 Vibe Mode, Nyan Cat. Both load at the SAME
     *  intrinsic size as the original Claude mark. */
    private val claudeAvatar: Icon = IconLoader.getIcon("/icons/claude.svg", ChatTheme::class.java)
    private val vibeAvatar: Icon = IconLoader.getIcon("/icons/claude-vibe.svg", ChatTheme::class.java)
    val avatar: Icon get() = if (vibeMode) vibeAvatar else claudeAvatar

    /** An avatar holder that re-reads [avatar] on every paint (so Vibe Mode swaps the glyph live, driven by the
     *  composer's repaint loop) and re-reports its size on every layout (so the bigger helmet gets the room it
     *  needs). Painted centred so the swap doesn't shift the baseline. Use instead of `JLabel(avatar)`. */
    fun avatarLabel(): javax.swing.JComponent = object : javax.swing.JComponent() {
        init { isOpaque = false }
        override fun getPreferredSize() = java.awt.Dimension(avatar.iconWidth, avatar.iconHeight)
        override fun paintComponent(g: Graphics) {
            val ic = avatar
            ic.paintIcon(this, g, (width - ic.iconWidth) / 2, (height - ic.iconHeight) / 2)
        }
    }

    /** Native tool-call + attachment icons — the plugin's own visual identity. */
    val iconToolBash: Icon = IconLoader.getIcon("/icons/tool-bash.svg", ChatTheme::class.java)
    val iconToolRead: Icon = IconLoader.getIcon("/icons/tool-read.svg", ChatTheme::class.java)
    val iconToolEdit: Icon = IconLoader.getIcon("/icons/tool-edit.svg", ChatTheme::class.java)
    val iconToolSearch: Icon = IconLoader.getIcon("/icons/tool-search.svg", ChatTheme::class.java)
    val iconToolWeb: Icon = IconLoader.getIcon("/icons/tool-web.svg", ChatTheme::class.java)
    val iconToolTask: Icon = IconLoader.getIcon("/icons/tool-task.svg", ChatTheme::class.java)
    val iconToolGeneric: Icon = IconLoader.getIcon("/icons/tool-generic.svg", ChatTheme::class.java)
    val iconAttach: Icon = vibeIcon(IconLoader.getIcon("/icons/attach.svg", ChatTheme::class.java))
    val iconAttachmentSelection: Icon = vibeIcon(IconLoader.getIcon("/icons/attachment-selection.svg", ChatTheme::class.java))
    val iconAttachmentImage: Icon = vibeIcon(IconLoader.getIcon("/icons/attachment-image.svg", ChatTheme::class.java))

    /** Attachment chip icon for an editor selection (snippet). */
    val iconSelection: Icon = vibeIcon(IconLoader.getIcon("/icons/attachment-selection.svg", ChatTheme::class.java))
    /** Attachment chip icon for an image (clipboard paste / drag&drop). */
    val iconImage: Icon = vibeIcon(IconLoader.getIcon("/icons/attachment-image.svg", ChatTheme::class.java))

    // Per-chip glyphs for the segmented options bar (model · permission mode · effort · thinking) — they let
    // each pill read at a glance from its icon, so the label can shrink to just the live value.
    // Provider brand marks — loaded WITHOUT vibeIcon so the brand colours survive theme + Vibe Mode (a
    // recoloured Anthropic/DeepSeek logo would stop reading as the brand). Fixed-hex SVGs, so IconLoader
    // doesn't theme-patch them either.
    val iconProviderAnthropic: Icon = IconLoader.getIcon("/icons/provider-anthropic.svg", ChatTheme::class.java)
    val iconProviderDeepseek: Icon = IconLoader.getIcon("/icons/provider-deepseek.svg", ChatTheme::class.java)

    /** The brand mark for [provider], shown on the composer's provider chip and the provider menu. */
    fun providerIcon(provider: dev.lain.claudejb.settings.Provider): Icon = when (provider) {
        dev.lain.claudejb.settings.Provider.ANTHROPIC -> iconProviderAnthropic
        dev.lain.claudejb.settings.Provider.DEEPSEEK -> iconProviderDeepseek
    }

    val iconChipModel: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-model.svg", ChatTheme::class.java))
    val iconChipMode: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-mode.svg", ChatTheme::class.java))
    val iconChipEffort: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-effort.svg", ChatTheme::class.java))
    val iconChipThinking: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-thinking.svg", ChatTheme::class.java))
    /** Coral sparkle shown on the thinking chip while extended thinking is on (active-state accent). */
    val iconChipThinkingOn: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-thinking-on.svg", ChatTheme::class.java))

    /** Follow-output toggle glyphs (double chevron): dim when off, coral when actively following the stream. */
    val iconFollow: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-follow.svg", ChatTheme::class.java))
    val iconFollowOn: Icon = vibeIcon(IconLoader.getIcon("/icons/chip-follow-on.svg", ChatTheme::class.java))

    /** Vibe Mode toggle glyphs: the Claude star when off, Nyan Cat when the rainbow show is on. Scaled up from the
     *  16px base so Nyan's detail (rainbow trail, pop-tart, face) actually reads in the composer button. */
    val iconVibe: Icon =
        com.intellij.util.IconUtil.scale(IconLoader.getIcon("/icons/claude.svg", ChatTheme::class.java), null, 1.4f)
    val iconVibeOn: Icon =
        com.intellij.util.IconUtil.scale(IconLoader.getIcon("/icons/claude-vibe.svg", ChatTheme::class.java), null, 1.4f)

    /** The tool-window stripe icon (its default), restored when Vibe Mode turns off. */
    val iconToolWindow: Icon = IconLoader.getIcon("/icons/toolwindow.svg", ChatTheme::class.java)

    // Cached per-category tool icons backing [toolIcon].
    private val toolBash    = vibeIcon(IconLoader.getIcon("/icons/tool-bash.svg", ChatTheme::class.java))
    private val toolRead    = vibeIcon(IconLoader.getIcon("/icons/tool-read.svg", ChatTheme::class.java))
    private val toolEdit    = vibeIcon(IconLoader.getIcon("/icons/tool-edit.svg", ChatTheme::class.java))
    private val toolSearch  = vibeIcon(IconLoader.getIcon("/icons/tool-search.svg", ChatTheme::class.java))
    private val toolWeb     = vibeIcon(IconLoader.getIcon("/icons/tool-web.svg", ChatTheme::class.java))
    private val toolTask    = vibeIcon(IconLoader.getIcon("/icons/tool-task.svg", ChatTheme::class.java))
    private val toolGeneric = vibeIcon(IconLoader.getIcon("/icons/tool-generic.svg", ChatTheme::class.java))

    /** Custom icon for a tool-call card by tool name. */
    fun toolIcon(toolName: String?): Icon = when (toolName) {
        "Bash" -> toolBash
        "Read" -> toolRead
        "Edit", "Write", "MultiEdit", "NotebookEdit" -> toolEdit
        "Grep", "Glob" -> toolSearch
        "WebFetch", "WebSearch" -> toolWeb
        "Task" -> toolTask
        else -> toolGeneric
    }

    val body: JBFont get() = JBFont.label()
    val small: JBFont get() = JBFont.medium()
    val mono: JBFont get() = JBFont.create(java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, JBFont.label().size))

    /**
     * The composer prompt font: the IDE **editor's** configured typeface (typically a mono like JetBrains Mono),
     * at the UI-scaled label size, so typing a prompt feels code-native and consistent with the editor you read.
     * A `get()` so it re-resolves when the chat is rebuilt after a theme/font change.
     */
    val promptFont: JBFont get() = JBFont.create(
        java.awt.Font(
            EditorColorsManager.getInstance().globalScheme.editorFontName,
            java.awt.Font.PLAIN,
            JBFont.label().size + JBUIScale.scale(1),
        ),
    )

    private fun editorBg(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

    fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /**
     * A panel that paints a rounded background (and optional border), for bubbles and cards.
     *
     * When [shadow] is on it also paints a soft drop shadow under the card for a touch of depth; the shadow
     * is drawn inside the component's own bounds (a few px reserved at the bottom/right by the empty border
     * the caller sets), so it never bleeds into siblings. Color is derived from the IDE theme so it stays
     * subtle on both light and dark schemes.
     */
    open class RoundedPanel(
        private val arc: Int,
        fill: Color?,
        line: Color? = null,
        private val shadow: Boolean = false,
    ) : JPanel() {
        /** Fill colour; settable so callers can recolour the card on hover and repaint. */
        var fill: Color? = fill
            set(value) { field = value; repaint() }

        /** Border colour; settable so callers can recolour the box (e.g. a tool's lifecycle state) and repaint. */
        var line: Color? = line
            set(value) { field = value; repaint() }

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val a = JBUIScale.scale(arc)
                // Reserve a hair of room for the shadow so the card body doesn't touch the row edge.
                val pad = if (shadow) JBUIScale.scale(2) else 0
                val w = width - pad
                val h = height - pad
                if (shadow) {
                    val offset = JBUIScale.scale(1)
                    g2.color = shadowColor()
                    g2.fillRoundRect(offset, offset + pad, w - offset, h - offset, a, a)
                }
                if (fill != null) {
                    g2.color = fill
                    g2.fillRoundRect(0, 0, w, h, a, a)
                }
                if (line != null) {
                    if (vibeMode) {
                        // 🌈 Vibe Mode: every bordered box (tool cards, error cards, chips…) gets an animated
                        // two-stop rainbow outline instead of its themed hairline.
                        g2.stroke = java.awt.BasicStroke(JBUIScale.scale(1.6f))
                        g2.paint = java.awt.GradientPaint(
                            0f, 0f, vibeColorAt(0f), w.toFloat(), h.toFloat(), vibeColorAt(0.45f),
                        )
                    } else {
                        g2.color = line
                    }
                    g2.drawRoundRect(0, 0, w - 1, h - 1, a, a)
                }
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        /** A faint, theme-aware shadow tint: darker than the background, low alpha so it only hints depth. */
        private fun shadowColor(): Color = SHADOW
    }
}
