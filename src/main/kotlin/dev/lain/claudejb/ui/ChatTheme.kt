package dev.lain.claudejb.ui

import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * The host's view of the **IDE theme** (light/dark): the surface behind the chat page comes from the platform
 * ([UIUtil]) so what the plugin paints blends into the IDE. Only the Claude **coral accent** is fixed — it is
 * the product's identity.
 *
 * Read by `JcefTheme`, which turns the accent and the Vibe Mode flag into what the chat page is themed with,
 * and by `JcefChatPanel`, the Swing container the browser sits in — which is why this is host-side at all.
 * `JcefTheme` takes the platform's text and border colours straight from the platform; what lives here is what
 * the host and the page have to agree on.
 *
 * Colors are computed properties resolved from the platform on each read, so they reflect the IDE theme in
 * effect when the component is built or the payload is sent.
 */
object ChatTheme {

    /** Saturation/brightness of the Vibe Mode accent — vivid, but not so saturated that text on it stops reading. */
    private const val VIBE_SATURATION = 0.85f
    private const val VIBE_BRIGHTNESS = 1.0f

    /** The Claude coral, fixed product identity. */
    private val CORAL = Color(0xD97757)

    /**
     * The accent reported while Vibe Mode is on. The rainbow is animated **in the page** (`app-core-theme.js`,
     * off the `vibe` flag `JcefTheme` ships); what the host reports is one fixed hue of the same spectrum.
     */
    private val VIBE_ACCENT = Color(Color.HSBtoRGB(0f, VIBE_SATURATION, VIBE_BRIGHTNESS))

    val BG: Color get() = UIUtil.getPanelBackground() // transcript / composer surface

    /**
     * 🌈 **Vibe Mode** (a gag toggle): when on, [ACCENT] becomes the rainbow accent. Off by default, toggled
     * from the composer, and **global** — one chat's toggle re-themes every surface that reads [ACCENT].
     */
    @Volatile var vibeMode: Boolean = false
        private set

    /** Flips Vibe Mode globally. `JcefTheme` ships the flag to the web app, which is what animates. */
    fun setVibeMode(on: Boolean) {
        vibeMode = on
    }

    /** Claude coral — links, send, avatar. In Vibe Mode it becomes the rainbow accent. */
    val ACCENT: Color get() = if (vibeMode) VIBE_ACCENT else CORAL
}
