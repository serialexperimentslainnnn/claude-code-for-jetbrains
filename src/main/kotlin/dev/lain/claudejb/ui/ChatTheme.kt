package dev.lain.claudejb.ui

import com.intellij.util.ui.UIUtil
import java.awt.Color

object ChatTheme {

    private const val VIBE_SATURATION = 0.85f
    private const val VIBE_BRIGHTNESS = 1.0f

    private val CORAL = Color(0xD97757)

    private val VIBE_ACCENT = Color(Color.HSBtoRGB(0f, VIBE_SATURATION, VIBE_BRIGHTNESS))

    val BG: Color get() = UIUtil.getPanelBackground()

    @Volatile var vibeMode: Boolean = false
        private set

    fun setVibeMode(on: Boolean) {
        vibeMode = on
    }

    val ACCENT: Color get() = if (vibeMode) VIBE_ACCENT else CORAL
}
