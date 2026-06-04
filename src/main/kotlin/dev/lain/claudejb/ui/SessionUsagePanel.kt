package dev.lain.claudejb.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.SessionCostUsage
import dev.lain.claudejb.session.ClaudeSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * Native, IDE-themed readout of a session's live consumption, shown above the composer. Built from real Swing
 * components ([JProgressBar] + [JBLabel]) — not custom Graphics2D painting — so it looks like the rest of the IDE.
 *
 * Three stacked rows, each hidden cleanly when it has no data:
 *  1. **Context window** — a labelled progress bar of `ContextUsage.percentage` (the authoritative figure the
 *     binary returns from `get_context_usage`), with "N / M (X%)" and threshold colour.
 *  2. **Session tokens** — the **authoritative cumulative** breakdown from `get_session_cost.apiUsage`
 *     (input · cache write · cache read · output), shown **inline** (not in a tooltip). Falls back to the live
 *     folded counters until the first session-cost poll returns.
 *  3. **Quota / reset** — a progress bar with utilization %, the window label, the reset countdown **and** the
 *     absolute reset hour; a non-colour ⚠ marker when near the limit. Hidden when `rateLimit == null`.
 *
 * ## Public API (driven by [ChatPanel])
 *  - [bind] — attach the session (call once); the panel becomes visible immediately.
 *  - [refresh] — recompute from the bound session + last context/api usage. EDT-only.
 *  - [setContextUsage] — feed the latest [ContextUsage] (or null to clear the context row).
 *  - [setApiUsage] — feed the latest authoritative `apiUsage` from `get_session_cost` (or null).
 *
 * Pure computation (formatTokens / threshold colour / countdown / reset hour / context fraction) lives in the
 * companion so it stays unit-testable without a Swing instance.
 */
class SessionUsagePanel : JBPanel<SessionUsagePanel>(VerticalLayout(JBUI.scale(3))) {

    private var session: ClaudeSession? = null
    private var contextUsage: ContextUsage? = null
    private var apiUsage: SessionCostUsage? = null

    // --- context row ---
    private val contextTitle = dimLabel("Context")
    private val contextValue = valueLabel()
    private val contextBar = slimBar()
    private val contextRow = labelledBar(contextTitle, contextValue, contextBar)

    // --- session-token breakdown row (inline, not a tooltip) ---
    // Header: "Session" + the output figure (the headline number). Detail: the full in/cache/out breakdown on
    // its own full-width line that WRAPS (HTML) so nothing is clipped in a narrow tool window.
    private val breakdownTitle = dimLabel("Session")
    private val breakdownValue = valueLabel()
    private val breakdownDetail = JBLabel().apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
    }
    private val breakdownRow = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(1))).apply {
        isOpaque = false
        add(headerRow(breakdownTitle, breakdownValue))
        add(breakdownDetail)
    }

    // --- quota row ---
    private val quotaTitle = dimLabel("Quota")
    private val quotaValue = valueLabel()
    private val quotaBar = slimBar()
    private val quotaRow = labelledBar(quotaTitle, quotaValue, quotaBar)

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 4, 6, 4)
        add(contextRow)
        add(breakdownRow)
        add(quotaRow)
        isVisible = false
    }

    // -----------------------------------------------------------------------
    // Public API (EDT)
    // -----------------------------------------------------------------------

    fun bind(session: ClaudeSession) {
        this.session = session
        refresh()
    }

    fun setContextUsage(usage: ContextUsage?) {
        contextUsage = usage
        refresh()
    }

    /** Feed the authoritative cumulative usage from `get_session_cost.apiUsage` (or null to fall back to live counters). */
    fun setApiUsage(usage: SessionCostUsage?) {
        apiUsage = usage
        refresh()
    }

    /** Recompute every row from the bound session + last context/api usage, then relayout. EDT-only. */
    fun refresh() {
        val s = session
        // Context row — visible once the binary has reported a window.
        val cu = contextUsage
        if (cu != null && (cu.maxTokens > 0 || cu.percentage > 0.0)) {
            val pct = (contextFraction(cu) * 100).toInt()
            contextBar.value = pct
            contextBar.foreground = thresholdColor(pct)
            contextValue.text = if (cu.maxTokens > 0)
                "${formatTokens(cu.totalTokens)} / ${formatTokens(cu.maxTokens)} · $pct%"
            else "${formatTokens(cu.totalTokens)} · $pct%"
            contextRow.isVisible = true
        } else {
            contextRow.isVisible = false
        }

        // Session token breakdown — authoritative apiUsage when available, else the live folded counters. Always
        // visible once a session is bound, so the box is present from load (starts at zeros, fills as you chat).
        if (s != null) {
            val inT: Long; val cw: Long; val cr: Long; val out: Long
            val au = apiUsage
            if (au != null) {
                inT = au.inputTokens; cw = au.cacheCreationInputTokens; cr = au.cacheReadInputTokens; out = au.outputTokens
            } else {
                inT = s.sessionInputTokens.toLong(); cw = s.sessionCacheCreationTokens.toLong()
                cr = s.sessionCacheReadTokens.toLong(); out = s.sessionOutputTokens.toLong()
            }
            // Headline = output (the figure people watch); detail = the full breakdown on its own wrapping line.
            breakdownValue.text = "out ${formatTokens(out)}"
            breakdownDetail.text = "<html>in ${formatTokens(inT)} · cache w ${formatTokens(cw)} · " +
                "cache r ${formatTokens(cr)} · out ${formatTokens(out)}</html>"
            breakdownRow.isVisible = true
        } else {
            breakdownRow.isVisible = false
        }

        // Quota row — only when the binary reports a rate limit. The binary often omits `utilization` in the
        // normal `allowed` state (claude.ai shows the % server-side), so when we have no real number we hide the
        // bar and show just the reset info — never a misleading 0% fill.
        val rl = s?.rateLimit
        if (rl != null) {
            val pct = rl.utilizationPercent() ?: if (rl.isExhausted) 100 else null
            quotaBar.isVisible = pct != null
            if (pct != null) {
                quotaBar.value = pct
                quotaBar.foreground = if (rl.isWarning) ChatTheme.WARNING else thresholdColor(pct)
            }
            quotaTitle.text = "Quota" + rl.windowLabel().let { if (it.isBlank()) "" else " · $it" }
            quotaValue.text = quotaValueText(rl)
            quotaRow.isVisible = true
        } else {
            quotaRow.isVisible = false
        }

        isVisible = contextRow.isVisible || breakdownRow.isVisible || quotaRow.isVisible
        revalidate()
        repaint()
    }

    /** Right-hand quota text: ⚠ marker + "exhausted"/"X%" + reset countdown and absolute hour + overage. */
    private fun quotaValueText(rl: RateLimitInfo): String {
        val now = System.currentTimeMillis() / 1000
        val countdown = countdownLabel(rl.resetsAt, now)
        val hour = resetHourLabel(rl.resetsAt)
        val resetInfo = when {
            countdown != null && hour != null -> "$countdown ($hour)"
            else -> countdown ?: hour
        }
        val usage = when {
            rl.isExhausted -> "exhausted"
            rl.utilizationPercent() != null -> "${rl.utilizationPercent()}%"
            else -> null
        }
        val warn = if (rl.isWarning) "⚠ " else ""
        val overage = if (rl.isUsingOverage) " · overage" else ""
        return (warn + listOfNotNull(usage, resetInfo).joinToString(" · ") + overage).ifBlank { "—" }
    }

    // -----------------------------------------------------------------------
    // Component builders
    // -----------------------------------------------------------------------

    private fun dimLabel(text: String) = JBLabel(text).apply {
        foreground = ChatTheme.TEXT_DIM
        font = ChatTheme.small
    }

    private fun valueLabel() = JBLabel().apply {
        foreground = ChatTheme.TEXT
        font = ChatTheme.small
        horizontalAlignment = JBLabel.RIGHT
    }

    private fun slimBar() = SlimBar()

    /**
     * A custom slim meter: a fully-rounded translucent track with a rounded-end fill — flatter and more modern than
     * the LAF's stock [javax.swing.JProgressBar]. The fill colour is the component's [getForeground] (callers set it
     * to the threshold/quota colour), so the existing `bar.value = …; bar.foreground = …` call sites are unchanged.
     */
    private class SlimBar : JComponent() {
        var value: Int = 0
            set(v) { field = v.coerceIn(0, 100); repaint() }

        init {
            isOpaque = false
            val h = JBUIScale.scale(5)
            preferredSize = Dimension(0, h)
            minimumSize = Dimension(0, h)
            maximumSize = Dimension(Int.MAX_VALUE, h)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width; val h = height
                val arc = h
                g2.color = Color(128, 128, 128, 45) // neutral, theme-agnostic track
                g2.fillRoundRect(0, 0, w, h, arc, arc)
                val fw = (w.toLong() * value / 100).toInt().coerceIn(0, w)
                if (fw > 0) {
                    g2.color = foreground ?: ChatTheme.ACCENT
                    g2.fillRoundRect(0, 0, fw.coerceAtLeast(h), h, arc, arc)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** A header line: left title + right-aligned value. */
    private fun headerRow(title: JBLabel, value: JBLabel): JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        add(title, BorderLayout.WEST)
        add(value, BorderLayout.CENTER)
    }

    /** A row with a [headerRow] and a thin meter underneath. */
    private fun labelledBar(title: JBLabel, value: JBLabel, bar: JComponent): JBPanel<*> =
        JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(1))).apply {
            isOpaque = false
            add(headerRow(title, value))
            add(bar)
        }

    companion object {
        // Threshold breakpoints for the green/amber/red colouring (percent).
        private const val GREEN_MAX = 59
        private const val AMBER_MAX = 84

        private val GREEN: Color get() = JBColor(Color(0x2E7D32), Color(0x66BB6A))
        private val AMBER: Color get() = JBColor(Color(0xB8860B), Color(0xE0A030))
        private val RED: Color get() = JBColor(Color(0xC62828), Color(0xEF5350))

        /** Green < 60, amber < 85, red otherwise. */
        fun thresholdColor(percent: Int): Color = when {
            percent <= GREEN_MAX -> GREEN
            percent <= AMBER_MAX -> AMBER
            else -> RED
        }

        /** 0..1 fraction of the context window in use, from `percentage` if given else `total/max`. */
        fun contextFraction(u: ContextUsage): Double {
            val frac = when {
                u.percentage > 0.0 -> if (u.percentage <= 1.0) u.percentage else u.percentage / 100.0
                u.maxTokens > 0 -> u.totalTokens.toDouble() / u.maxTokens
                else -> 0.0
            }
            return frac.coerceIn(0.0, 1.0)
        }

        /** Human token count ("950", "1.2k", "3.4M") — delegates to the shared [TokenFormat] so panels can't drift. */
        fun formatTokens(n: Long): String = TokenFormat.format(n)

        /**
         * "resets in Hh Mm" countdown from an epoch-seconds [resetsAt] relative to [nowSeconds]. Null when
         * there is no window; "resetting" once the window has elapsed; minutes-only under an hour.
         */
        fun countdownLabel(resetsAt: Long?, nowSeconds: Long): String? {
            if (resetsAt == null) return null
            val remaining = resetsAt - nowSeconds
            if (remaining <= 0) return "resetting"
            val h = remaining / 3600
            val m = (remaining % 3600) / 60
            return if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
        }

        /** Absolute local reset wall-clock ("HH:mm") for an epoch-seconds [resetsAt], or null when there's no window. */
        fun resetHourLabel(resetsAt: Long?): String? {
            if (resetsAt == null) return null
            return java.time.Instant.ofEpochSecond(resetsAt)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
}
